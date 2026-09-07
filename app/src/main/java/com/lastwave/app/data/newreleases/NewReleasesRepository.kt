package com.lastwave.app.data.newreleases

import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewReleasesRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
) {
    private val mutex = Mutex()
    private val albumQueue = ArrayDeque<YouTubePlaylistSummary>()
    private val seenVideoIds = mutableSetOf<String>()
    private var exploreContinuationToken: String? = null
    private var albumsContinuationToken: String? = null
    private var searchFallbackIndex = 0

    private val searchQueries = listOf(
        "new music 2026",
        "new songs 2026",
        "new music friday",
        "fresh drops hits",
        "trending new music",
        "latest releases 2026",
        "new music hotlist",
        "popular new songs",
        "new pop hits 2026",
        "new hip hop 2026",
        "new indie 2026",
        "new rock 2026",
    )

    suspend fun reset() = mutex.withLock {
        albumQueue.clear()
        seenVideoIds.clear()
        exploreContinuationToken = null
        albumsContinuationToken = null
        searchFallbackIndex = 0
    }

    suspend fun fetchInitialBatch(): List<YouTubeMusicTrack> = mutex.withLock {
        albumQueue.clear()
        seenVideoIds.clear()
        exploreContinuationToken = null
        albumsContinuationToken = null
        searchFallbackIndex = 0

        val results = mutableListOf<YouTubeMusicTrack>()

        coroutineScope {
            val exploreDeferred = async(Dispatchers.IO) {
                runCatching { innerTube.fetchNewReleasesPage(null) }.getOrNull()
            }
            val albumsGridDeferred = async(Dispatchers.IO) {
                runCatching { innerTube.fetchNewReleasesAlbumsGrid(null) }.getOrNull()
            }

            val exploreBatch = exploreDeferred.await()
            val albumsGridBatch = albumsGridDeferred.await()

            if (exploreBatch != null) {
                exploreContinuationToken = exploreBatch.continuationToken
                for (track in exploreBatch.directTracks) {
                    if (track.videoId.isNotBlank() && seenVideoIds.add(track.videoId)) {
                        results.add(track)
                    }
                }
                for (album in exploreBatch.albums) {
                    if (album.id.isNotBlank()) albumQueue.add(album)
                }
            }

            if (albumsGridBatch != null) {
                albumsContinuationToken = albumsGridBatch.second
                for (album in albumsGridBatch.first) {
                    if (album.id.isNotBlank() && albumQueue.none { it.id == album.id }) {
                        albumQueue.add(album)
                    }
                }
            }
        }

        // Expand first wave of release albums
        val initialAlbumsToExpand = mutableListOf<YouTubePlaylistSummary>()
        while (albumQueue.isNotEmpty() && initialAlbumsToExpand.size < 5) {
            initialAlbumsToExpand.add(albumQueue.removeFirst())
        }

        if (initialAlbumsToExpand.isNotEmpty()) {
            val expandedTracks = expandAlbums(initialAlbumsToExpand)
            for (track in expandedTracks) {
                if (track.videoId.isNotBlank() && seenVideoIds.add(track.videoId)) {
                    results.add(track)
                }
            }
        }

        // If initial batch is still small, expand another batch
        if (results.size < 20 && albumQueue.isNotEmpty()) {
            val secondWave = mutableListOf<YouTubePlaylistSummary>()
            while (albumQueue.isNotEmpty() && secondWave.size < 4) {
                secondWave.add(albumQueue.removeFirst())
            }
            if (secondWave.isNotEmpty()) {
                val moreTracks = expandAlbums(secondWave)
                for (track in moreTracks) {
                    if (track.videoId.isNotBlank() && seenVideoIds.add(track.videoId)) {
                        results.add(track)
                    }
                }
            }
        }

        // Fallback search if network was limited
        if (results.size < 10) {
            val fallbackTracks = runCatching {
                innerTube.searchSongs(searchQueries[searchFallbackIndex++ % searchQueries.size], limit = 30)
            }.getOrDefault(emptyList())
            for (track in fallbackTracks) {
                if (track.videoId.isNotBlank() && seenVideoIds.add(track.videoId)) {
                    results.add(track)
                }
            }
        }

        results
    }

    suspend fun fetchNextBatch(targetCount: Int = 15): List<YouTubeMusicTrack> = mutex.withLock {
        val newTracks = mutableListOf<YouTubeMusicTrack>()

        // Replenish album queue if low
        if (albumQueue.size < 8) {
            if (!albumsContinuationToken.isNullOrBlank()) {
                val gridBatch = runCatching {
                    innerTube.fetchNewReleasesAlbumsGrid(albumsContinuationToken)
                }.getOrNull()
                if (gridBatch != null) {
                    albumsContinuationToken = gridBatch.second
                    for (album in gridBatch.first) {
                        if (album.id.isNotBlank() && albumQueue.none { it.id == album.id }) {
                            albumQueue.add(album)
                        }
                    }
                }
            } else if (!exploreContinuationToken.isNullOrBlank()) {
                val exploreBatch = runCatching {
                    innerTube.fetchNewReleasesPage(exploreContinuationToken)
                }.getOrNull()
                if (exploreBatch != null) {
                    exploreContinuationToken = exploreBatch.continuationToken
                    for (track in exploreBatch.directTracks) {
                        if (track.videoId.isNotBlank() && seenVideoIds.add(track.videoId)) {
                            newTracks.add(track)
                        }
                    }
                    for (album in exploreBatch.albums) {
                        if (album.id.isNotBlank() && albumQueue.none { it.id == album.id }) {
                            albumQueue.add(album)
                        }
                    }
                }
            }
        }

        // Expand next set of albums
        val albumsToExpand = mutableListOf<YouTubePlaylistSummary>()
        while (albumQueue.isNotEmpty() && albumsToExpand.size < 4 && newTracks.size < targetCount) {
            albumsToExpand.add(albumQueue.removeFirst())
        }

        if (albumsToExpand.isNotEmpty()) {
            val expanded = expandAlbums(albumsToExpand)
            for (track in expanded) {
                if (track.videoId.isNotBlank() && seenVideoIds.add(track.videoId)) {
                    newTracks.add(track)
                }
            }
        }

        // If still under target count or queue exhausted, query search fallback for fresh drops
        if (newTracks.size < targetCount) {
            val query = searchQueries[searchFallbackIndex++ % searchQueries.size]
            val searchResults = runCatching {
                innerTube.searchSongs(query, limit = 30)
            }.getOrDefault(emptyList())
            for (track in searchResults) {
                if (track.videoId.isNotBlank() && seenVideoIds.add(track.videoId)) {
                    newTracks.add(track)
                }
            }
        }

        newTracks
    }

    private suspend fun expandAlbums(albums: List<YouTubePlaylistSummary>): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        coroutineScope {
            albums.map { album ->
                async {
                    runCatching {
                        innerTube.fetchAlbumPage(
                            browseId = album.id,
                            albumTitleFallback = album.title,
                            artistFallback = album.author.orEmpty(),
                        )
                    }.getOrNull()?.tracks.orEmpty().mapNotNull { it.toYouTubeMusicTrack() }
                }
            }.awaitAll().flatten()
        }
    }

    private fun PlayableTrack.toYouTubeMusicTrack(): YouTubeMusicTrack? {
        val id = videoId?.takeIf(String::isNotBlank) ?: return null
        return YouTubeMusicTrack(
            videoId = id,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            durationSeconds = null,
        )
    }
}
