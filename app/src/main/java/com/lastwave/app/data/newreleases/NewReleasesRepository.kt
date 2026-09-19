package com.lastwave.app.data.newreleases

import com.lastwave.app.data.feed.FeedRepository
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

internal fun getLocalizedSearchQueries(hl: String, year: Int): List<String> = when (hl.lowercase().substringBefore('-')) {
    "tr" -> listOf("yeni çıkanlar $year", "yeni şarkılar $year", "türkçe yeni müzik $year", "yeni albümler $year")
    "es" -> listOf("música nueva $year", "canciones nuevas $year", "nuevos lanzamientos $year", "éxitos $year")
    "fr" -> listOf("nouvelle musique $year", "nouveautés $year", "nouvelles chansons $year", "hits français $year")
    "de" -> listOf("neue musik $year", "neue lieder $year", "aktuelle hits $year", "neuerscheinungen $year")
    "ru" -> listOf("новинки музыки $year", "новые песни $year", "свежая музыка $year", "русские хиты $year")
    "pt" -> listOf("músicas novas $year", "lançamentos $year", "novas músicas $year", "hits brasil $year")
    "id" -> listOf("lagu baru $year", "musik terbaru $year", "rilisan terbaru $year")
    "hi" -> listOf("नए गाने $year", "new hindi songs $year", "latest bollywood $year")
    "ja" -> listOf("新曲 $year", "最新音楽 $year", "最新リリース $year", "J-POP 新曲 $year")
    "ko" -> listOf("신곡 $year", "최신 음악 $year", "최신 가요 $year", "K-POP 신곡 $year")
    "zh" -> listOf("最新歌曲 $year", "新歌推荐 $year", "华语新歌 $year")
    "ar" -> listOf("أغاني جديدة $year", "جديد الموسيقى $year", "أحدث الأغاني $year")
    else -> listOf("new music $year", "new songs $year", "new music friday", "latest releases $year")
}

@Singleton
class NewReleasesRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val feedRepository: FeedRepository,
) {
    private val mutex = Mutex()
    private val albumQueue = ArrayDeque<YouTubePlaylistSummary>()
    private val seenAlbumIds = mutableSetOf<String>()
    private val seenVideoIds = mutableSetOf<String>()
    private val seenTokens = mutableSetOf<String>()
    private var exploreToken: String? = null
    private var albumsToken: String? = null
    private var searchIndex = 0
    private var gridLoaded = false

    private fun getLocalizedSearchQueries(): List<String> {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val (hl, _) = innerTube.getEffectiveHlGl()
        return getLocalizedSearchQueries(hl, year)
    }

    val hasMore: Boolean
        get() = !gridLoaded || albumQueue.isNotEmpty() || exploreToken != null || albumsToken != null || searchIndex < getLocalizedSearchQueries().size

    private fun clear() {
        albumQueue.clear()
        seenAlbumIds.clear()
        seenVideoIds.clear()
        seenTokens.clear()
        exploreToken = null
        albumsToken = null
        searchIndex = 0
        gridLoaded = false
    }

    suspend fun reset() = mutex.withLock { clear() }

    suspend fun fetchInitialBatch(): List<YouTubeMusicTrack> = mutex.withLock {
        clear()

        // 1. Maintain 100% consistency with the Home screen:
        // Load the exact releases from the Home "New Releases" section, starting with
        // the album whose artwork is used as the "New Releases" tile thumbnail.
        val homeReleases = feedRepository.getCachedFeed()?.newReleases.orEmpty()
        if (homeReleases.isNotEmpty()) {
            enqueue(homeReleases)
        } else {
            // If the Home feed has not cached newReleases yet, fetch regional new releases directly
            val freshReleases = runCatching { innerTube.fetchNewReleases() }.getOrDefault(emptyList())
            if (freshReleases.isNotEmpty()) {
                enqueue(freshReleases)
            }
        }

        // 2. Pre-populate additional regional releases from InnerTube's albums grid for pagination
        if (albumQueue.size < 15) {
            val grid = runCatching { innerTube.fetchNewReleasesAlbumsGrid() }.getOrNull()
            if (grid != null) {
                gridLoaded = true
                albumsToken = grid.second
                enqueue(grid.first)
            }
        }

        nextBatch()
    }

    suspend fun fetchNextBatch(): List<YouTubeMusicTrack> = mutex.withLock {
        nextBatch()
    }

    private suspend fun nextBatch(): List<YouTubeMusicTrack> {
        val collected = mutableListOf<YouTubeMusicTrack>()
        val minBatchSize = 10

        while (hasMore) {
            if (albumQueue.isNotEmpty()) {
                val album = albumQueue.first
                val page = try {
                    innerTube.fetchAlbumPage(album.id, album.title, album.author.orEmpty())
                } catch (_: Exception) {
                    null
                }
                albumQueue.removeFirst()
                if (page != null) {
                    val tracks = page.tracks.mapNotNull { it.toYouTubeMusicTrack() }
                    val fresh = unique(tracks)
                    collected.addAll(fresh)
                    if (collected.size >= minBatchSize) {
                        return collected
                    }
                }
                continue
            }

            if (collected.isNotEmpty()) {
                return collected
            }

            if (!gridLoaded) {
                val grid = innerTube.fetchNewReleasesAlbumsGrid()
                gridLoaded = true
                albumsToken = grid.second
                enqueue(grid.first)
                continue
            }

            albumsToken?.let { token ->
                val batch = innerTube.fetchNewReleasesAlbumsGrid(token)
                seenTokens.add(token)
                albumsToken = batch.second?.takeUnless { it in seenTokens }
                enqueue(batch.first)
            } ?: exploreToken?.let { token ->
                val batch = innerTube.fetchNewReleasesPage(token)
                seenTokens.add(token)
                exploreToken = batch.continuationToken?.takeUnless { it in seenTokens }
                enqueue(batch.albums)
                val fresh = unique(batch.directTracks)
                if (fresh.isNotEmpty()) return fresh
            } ?: run {
                val queries = getLocalizedSearchQueries()
                val query = queries.getOrNull(searchIndex) ?: return emptyList()
                val tracks = innerTube.searchSongs(query, limit = 30, prefetchStreams = false)
                searchIndex++
                val fresh = unique(tracks)
                if (fresh.isNotEmpty()) return fresh
            }
        }
        return collected
    }

    private fun enqueue(albums: List<YouTubePlaylistSummary>) {
        albums.filter {
            it.id.isNotBlank() && seenAlbumIds.add(it.id)
        }.forEach(albumQueue::addLast)
    }

    private fun unique(tracks: List<YouTubeMusicTrack>) =
        tracks.filter { it.videoId.isNotBlank() && seenVideoIds.add(it.videoId) }

    private fun PlayableTrack.toYouTubeMusicTrack(): YouTubeMusicTrack? {
        val id = videoId?.takeIf(String::isNotBlank) ?: return null
        return YouTubeMusicTrack(videoId = id, title = title, artist = artist,
            album = album, artworkUrl = artworkUrl, durationSeconds = null)
    }
}
