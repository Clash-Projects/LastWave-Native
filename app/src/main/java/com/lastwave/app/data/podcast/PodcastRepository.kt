package com.lastwave.app.data.podcast

import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val settingsPreferences: SettingsPreferences,
    private val progressStore: PodcastProgressStore,
) {
    /** False when the user turned podcasts off in Settings. */
    val enabled: Flow<Boolean> = settingsPreferences.settings
        .map { it.podcastsEnabled }
        .distinctUntilChanged()

    private val homeMutex = Mutex()
    private val showMutex = Mutex()
    private val cacheLock = Any()

    @Volatile
    private var homeCache: List<PodcastShelf>? = null
    @Volatile
    private var homeCachedAtMillis: Long = 0L
    private var showCache: MutableMap<String, PodcastShowPage> = mutableMapOf()

    suspend fun isEnabledNow(): Boolean = settingsPreferences.settings.first().podcastsEnabled

    suspend fun home(forceRefresh: Boolean = false): List<PodcastShelf> {
        if (!isEnabledNow()) return emptyList()
        val cached = synchronized(cacheLock) { homeCache }
        if (!forceRefresh && cached != null &&
            System.currentTimeMillis() - homeCachedAtMillis < HOME_CACHE_TTL_MS
        ) {
            return cached
        }
        return homeMutex.withLock {
            val inside = synchronized(cacheLock) { homeCache }
            if (!forceRefresh && inside != null &&
                System.currentTimeMillis() - homeCachedAtMillis < HOME_CACHE_TTL_MS
            ) {
                return@withLock inside
            }
            if (!isEnabledNow()) return@withLock emptyList<PodcastShelf>()
            val shelves = runCatching { innerTube.fetchPodcastHome() }.getOrDefault(emptyList())
            if (shelves.isNotEmpty()) {
                synchronized(cacheLock) {
                    homeCache = shelves
                    homeCachedAtMillis = System.currentTimeMillis()
                }
            }
            shelves
        }
    }

    suspend fun show(showId: String, forceRefresh: Boolean = false): PodcastShowPage? {
        if (showId.isBlank() || !isEnabledNow()) return null
        val cached = synchronized(cacheLock) { showCache[showId] }
        if (!forceRefresh && cached != null) return cached
        return showMutex.withLock {
            val inside = synchronized(cacheLock) { showCache[showId] }
            if (!forceRefresh && inside != null) return@withLock inside
            if (!isEnabledNow()) return@withLock null
            val page = runCatching { innerTube.fetchPodcastShow(showId) }.getOrNull()
                ?: return@withLock null
            synchronized(cacheLock) { showCache[showId] = page }
            page
        }
    }

    suspend fun moreEpisodes(showId: String, continuation: String?): PodcastEpisodePage {
        if (!isEnabledNow() || continuation.isNullOrBlank()) {
            return PodcastEpisodePage(episodes = emptyList())
        }
        val showTitle = synchronized(cacheLock) { showCache[showId]?.show?.title }
        return runCatching {
            innerTube.fetchPodcastShowContinuation(showId, continuation, showTitle)
        }.getOrDefault(PodcastEpisodePage(episodes = emptyList()))
    }

    suspend fun searchEpisodes(query: String): List<PodcastEpisode> {
        if (query.isBlank() || !isEnabledNow()) return emptyList()
        return runCatching { innerTube.searchPodcastEpisodes(query.trim()) }.getOrDefault(emptyList())
    }

    suspend fun resumePositionMs(episode: PodcastEpisode): Long =
        progressStore.resumePositionMs(episode.videoId, episode.durationMs ?: 0L)

    suspend fun latestIncompleteForShow(showId: String): PodcastProgress? =
        progressStore.latestIncompleteForShow(showId)

    fun progressForShow(showId: String): Flow<Map<String, PodcastProgress>> =
        progressStore.observeForShow(showId)

    private companion object {
        const val HOME_CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
