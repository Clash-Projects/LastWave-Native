package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.distinctSongs
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.distinctPlaylistSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImportManager @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val likedSongsManager: LikedSongsManager,
    private val csvPlaylistImporter: CsvPlaylistImporter,
    private val spotifyPlaylistImporter: SpotifyPlaylistImporter,
    private val appleMusicPlaylistImporter: AppleMusicPlaylistImporter,
) {

    companion object {
        /** True for the account's special Liked Music / Liked Videos playlist (LM / VLLM / LL / VLLL). */
        fun isYtLikedId(id: String): Boolean {
            val clean = id.trim()
            return clean == "LM" || clean == "VLLM" || clean.removePrefix("VL") == "LM" ||
                clean == "LL" || clean == "VLLL" || clean.removePrefix("VL") == "LL" ||
                clean == "FEmusic_liked_videos"
        }
    }

    suspend fun importYouTubePlaylist(
        playlist: YouTubePlaylistResult,
        selectedTracks: List<YouTubeMusicTrack> = playlist.tracks,
    ): SavedPlaylist = withContext(Dispatchers.IO) {
        // A fetched playlist can contain the same song twice (two uploads,
        // two videoIds); collapse it so an import never stacks a duplicate.
        val tracks = selectedTracks.distinctPlaylistSongs().map { yt ->
            GeneratedTrack(
                name = yt.title,
                artist = yt.artist,
                album = yt.album,
                artworkUrl = yt.artworkUrl,
                url = "https://music.youtube.com/watch?v=${yt.videoId}",
            )
        }

        playlistRepository.save(
            title = playlist.title,
            subtitle = "YouTube Music \u2022 ${tracks.size} tracks",
            mode = "custom",
            tracks = tracks,
        )
    }

    /**
     * Merges the account's YouTube Liked Music (LM) into the built-in local
     * Liked Songs playlist (deduped by strong song identity). Unlike normal
     * imports this never creates a separate `custom` playlist and never
     * creates a remote mapping — LM is not editable via the playlist-edit
     * API, so the local Liked Songs copy syncs as its own private
     * "Liked Songs" mirror instead.
     *
     * The merge itself runs inside [LikedSongsManager.mergeTracks] under the
     * shared liked mutation mutex, so a heart-tap racing this import can no
     * longer interleave a stale read-modify-write that duplicates entries.
     */
    suspend fun importYtLikedIntoLikedSongs(
        playlist: YouTubePlaylistResult,
        selectedTracks: List<YouTubeMusicTrack> = playlist.tracks,
    ): SavedPlaylist = withContext(Dispatchers.IO) {
        val tracks = selectedTracks.map { yt ->
            GeneratedTrack(
                name = yt.title,
                artist = yt.artist,
                album = yt.album,
                artworkUrl = yt.artworkUrl,
                url = "https://music.youtube.com/watch?v=${yt.videoId}",
            )
        }.distinctSongs()
        if (tracks.isEmpty()) return@withContext playlistRepository.ensureLikedSongs()
        likedSongsManager.mergeTracks(tracks)
        playlistRepository.ensureLikedSongs()
    }

    /**
     * Makes an owned account playlist local as a one-way copy. Unlike before,
     * this deliberately creates NO live two-way mapping and enrolls nothing
     * in background sync: importing a playlist only means "play it here", so
     * the app must never write back to the user's YouTube playlist on its
     * own. A per-playlist sync toggle in the UI remains the single explicit
     * opt-in that creates a live mirror.
     */
    suspend fun importOwnedYouTubePlaylist(playlist: YouTubePlaylistResult): SavedPlaylist =
        withContext(Dispatchers.IO) {
            // Re-import guard (replaces the old mapping lookup): same title
            // with the identical YouTube video set is already local.
            val incomingIds = playlist.tracks.map { it.videoId }.toSet()
            playlistRepository.getAll().firstOrNull { local ->
                local.title.equals(playlist.title, ignoreCase = true) &&
                    local.tracks.mapNotNull { it.youtubeVideoIdOrNull() }.toSet() == incomingIds
            }?.let { return@withContext it }
            importYouTubePlaylist(playlist)
        }

    suspend fun importCsvStream(
        inputStream: InputStream,
        filename: String,
    ): Pair<SavedPlaylist, CsvImportResult> = withContext(Dispatchers.IO) {
        val result = csvPlaylistImporter.parseAndMatchCsv(inputStream, filename)
        require(result.tracks.isNotEmpty()) {
            "No verified tracks found. Include track titles and artists, or YouTube links. Nothing was imported."
        }
        val fileType = filename.substringAfterLast('.', "File").uppercase()
        val saved = playlistRepository.save(
            title = result.suggestedTitle,
            subtitle = "$fileType Import \u2022 ${result.matchedCount} imported, ${result.totalRows - result.matchedCount} skipped",
            mode = "custom",
            tracks = result.tracks,
        )
        Pair(saved, result)
    }

    /**
     * Imports a **public** Spotify or Apple Music playlist from a pasted link.
     *
     * The provider page is scraped (no API key or account needed), each row is
     * matched to a playable track, and the result is stored as a `custom`
     * playlist. Rows that cannot be matched are skipped and reported in the
     * returned [ExternalImportResult].
     */
    suspend fun importExternalPlaylist(
        url: String,
    ): Pair<SavedPlaylist, ExternalImportResult> = withContext(Dispatchers.IO) {
        val source = ExternalPlaylistLink.detect(url)
            ?: throw IllegalArgumentException("Paste a Spotify or Apple Music playlist link.")

        val result = when (source) {
            ExternalPlaylistSource.SPOTIFY -> spotifyPlaylistImporter.fetchAndMatch(url)
            ExternalPlaylistSource.APPLE_MUSIC -> appleMusicPlaylistImporter.fetchAndMatch(url)
        }

        require(result.tracks.isNotEmpty()) {
            "No tracks from that playlist could be matched. Make sure the playlist is public."
        }

        val saved = playlistRepository.save(
            title = result.suggestedTitle,
            subtitle = "${result.source.label} Import \u2022 ${result.matchedCount} imported, ${result.totalRows - result.matchedCount} skipped",
            mode = "custom",
            tracks = result.tracks,
        )
        Pair(saved, result)
    }

    /**
     * Imports the user's Spotify Liked Songs collection into the local
     * Liked Songs playlist, deduped by strong song identity.
     */
    suspend fun importSpotifyLikedIntoLikedSongs(): Pair<SavedPlaylist, ExternalImportResult> =
        withContext(Dispatchers.IO) {
            val result = spotifyPlaylistImporter.fetchAndMatchLikedSongs()
            require(result.tracks.isNotEmpty()) {
                "No liked songs found on Spotify or none could be matched."
            }
            likedSongsManager.mergeTracks(result.tracks)
            val likedPlaylist = playlistRepository.ensureLikedSongs()
            Pair(likedPlaylist, result)
        }
}
