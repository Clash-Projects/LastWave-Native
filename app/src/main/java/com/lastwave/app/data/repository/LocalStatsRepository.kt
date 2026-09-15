package com.lastwave.app.data.repository

import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.playlist.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local Stats Mode for the Stats tab (HomeScreen).
 *
 * When Last.fm is NOT connected, the Stats page seamlessly switches here:
 * plays, top artists/tracks, and listening time are aggregated directly from
 * the local Room database — saved playlists (including Liked Songs) and
 * downloaded tracks — instead of user.getinfo / user.gettoptracks.
 *
 * This is the local-first counterpart to [HomeRepository.fetchStats]: same
 * [HomeStats] shape so the UI renders identically, with a banner pointing to
 * Settings → Integrations to sync global scrobbles.
 */
@Singleton
class LocalStatsRepository @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val downloadedTrackDao: DownloadedTrackDao,
) {
    data class LocalStats(
        val stats: HomeStats,
        val topTracks: List<HomeTrack>,
        val topArtists: List<HomeArtistItem>,
        val topAlbums: List<HomeAlbum>,
    )

    suspend fun load(): LocalStats {
        val playlists = runCatching { playlistRepository.getAll() }.getOrDefault(emptyList())
        val liked = runCatching { playlistRepository.getLikedSongs()?.tracks.orEmpty() }.getOrDefault(emptyList())
        val downloaded = runCatching { downloadedTrackDao.getAllList() }.getOrDefault(emptyList())

        val allTracks = (playlists.flatMap { it.tracks } + liked)
            .filter { it.name.isNotBlank() && it.artist.isNotBlank() }

        // Plays proxy: each saved occurrence counts once; duplicates across
        // playlists collapse for distinct counts but sum for total plays.
        val playCounts = allTracks.groupingBy { it.key }.eachCount()
        val totalPlays = playCounts.values.sum().toLong()
        val distinctTracks = playCounts.size.toLong()

        val artistPlays = allTracks.groupingBy { it.artist.trim().lowercase() }.eachCount()
        val artistDisplay = allTracks.groupBy { it.artist.trim().lowercase() }
            .mapValues { (_, tracks) -> tracks.firstOrNull()?.artist?.trim().orEmpty() }
        val distinctArtists = artistPlays.size.toLong()

        val albumKeys = allTracks.mapNotNull { it.album?.trim()?.takeIf(String::isNotBlank)?.lowercase() }
            .toSet().size.toLong()
        // Downloads contribute albums too (album column is authoritative there).
        val downloadAlbums = downloaded.mapNotNull { it.album.trim().takeIf(String::isNotBlank)?.lowercase() }
            .toSet().size.toLong()
        val distinctAlbums = maxOf(albumKeys, downloadAlbums)

        // Listening-time estimate reuses Home's 210s/track convention so the
        // timer reads consistently between local and Last.fm modes.
        val scrobbles = totalPlays + downloaded.size.toLong()

        val topTracks = playCounts.entries
            .sortedByDescending { it.value }
            .take(50)
            .mapNotNull { (key, count) ->
                val track = allTracks.firstOrNull { it.key == key } ?: return@mapNotNull null
                HomeTrack(
                    name = track.name,
                    artist = track.artist,
                    artworkUrl = track.artworkUrl,
                    timestampMillis = null,
                    playCount = count,
                )
            }

        val topArtists = artistPlays.entries
            .sortedByDescending { it.value }
            .take(30)
            .map { (key, count) ->
                val display = artistDisplay[key].orEmpty().ifBlank { key }
                val artwork = allTracks.firstOrNull {
                    it.artist.trim().lowercase() == key && !it.artworkUrl.isNullOrBlank()
                }?.artworkUrl
                HomeArtistItem(name = display, playCount = count.toLong(), artworkUrl = artwork)
            }

        // Top albums: group Room tracks + downloads by artist|album. Singles
        // without an album name are skipped — they already surface in tracks.
        data class AlbumSeed(val artist: String, val title: String, val artworkUrl: String?)
        val albumSeeds = allTracks.mapNotNull { t ->
            val album = t.album?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            AlbumSeed(t.artist.trim(), album, t.artworkUrl)
        } + downloaded.mapNotNull { d ->
            val album = d.album.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            AlbumSeed(d.artist.trim().ifBlank { "Unknown artist" }, album, d.artworkUrl)
        }
        val albumGroups = albumSeeds.groupBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }
        val topAlbums = albumGroups.entries
            .sortedByDescending { it.value.size }
            .take(10)
            .map { (_, seeds) ->
                val first = seeds.first()
                HomeAlbum(
                    name = first.title,
                    artist = first.artist,
                    artworkUrl = seeds.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    playCount = seeds.size.toLong(),
                )
            }

        return LocalStats(
            stats = HomeStats(
                scrobbles = scrobbles,
                trackCount = distinctTracks,
                artistCount = distinctArtists,
                albumCount = distinctAlbums,
                avatarUrl = null,
            ),
            topTracks = topTracks,
            topArtists = topArtists,
            topAlbums = topAlbums,
        )
    }
}
