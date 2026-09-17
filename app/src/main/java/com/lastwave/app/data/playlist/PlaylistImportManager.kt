package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.ytmusic.YtMusicPreferences
import com.lastwave.app.data.ytmusic.YtPlaylistMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImportManager @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val innerTube: InnerTubeMusicApi,
    private val csvPlaylistImporter: CsvPlaylistImporter,
    private val ytMusicPreferences: YtMusicPreferences,
    private val spotifyPlaylistImporter: SpotifyPlaylistImporter,
    private val appleMusicPlaylistImporter: AppleMusicPlaylistImporter,
) {

    suspend fun importYouTubePlaylist(
        playlist: YouTubePlaylistResult,
        selectedTracks: List<com.lastwave.app.data.music.YouTubeMusicTrack> = playlist.tracks,
    ): SavedPlaylist = withContext(Dispatchers.IO) {
        val tracks = selectedTracks.map { yt ->
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

    /** Makes an owned account playlist local while retaining its live two-way mapping. */
    suspend fun importOwnedYouTubePlaylist(playlist: YouTubePlaylistResult): SavedPlaylist =
        ytMusicPreferences.playlistSyncMutex.withLock {
            withContext(Dispatchers.IO) {
                val mappings = ytMusicPreferences.mappings().toMutableMap()
                val remoteId = playlist.id.removePrefix("VL")
                val existingIds = mappings.filterValues {
                    it.remotePlaylistId.removePrefix("VL") == remoteId
                }.keys
                playlistRepository.getAll().firstOrNull { it.id in existingIds }?.let { return@withContext it }
                val saved = importYouTubePlaylist(playlist)
                mappings[saved.id] = YtPlaylistMapping(
                    remotePlaylistId = remoteId,
                    remoteTitle = playlist.title,
                    lastSyncAtMillis = System.currentTimeMillis(),
                    lastSyncedVideoIds = playlist.tracks.map { it.videoId },
                    deleteRemoteWithLocal = false,
                )
                ytMusicPreferences.setMappings(mappings)
                val selectedIds = ytMusicPreferences.syncedPlaylistIds.first()
                if (selectedIds != null && saved.id !in selectedIds) {
                    ytMusicPreferences.setSyncedPlaylistIds(selectedIds + saved.id)
                }
                saved
            }
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
}
