package com.lastwave.app.data.repository

import android.util.Log
import com.lastwave.app.data.local.db.RelatedGraphDao
import com.lastwave.app.data.local.db.RelatedGraphEdgeEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val FRESH_TTL_MS: Long = 24 * 60 * 60 * 1000L
internal const val PRUNE_AFTER_MS: Long = 90L * 24 * 60 * 60 * 1000
internal const val MAX_EDGES: Int = 100_000

internal fun isFresh(ageMs: Long, ttlMs: Long = FRESH_TTL_MS): Boolean = ageMs <= ttlMs

internal fun needsBackgroundRefresh(
    ageMs: Long,
    cachedCount: Int,
    limit: Int,
    ttlMs: Long = FRESH_TTL_MS,
): Boolean = !isFresh(ageMs, ttlMs) || cachedCount < limit

internal fun mergeFetchedEdges(
    existing: List<RelatedGraphEdgeEntity>,
    fetched: List<YouTubeMusicTrack>,
    sourceVideoId: String,
    nowMillis: Long,
): List<RelatedGraphEdgeEntity> {
    val existingByRelatedId = existing.associateBy { it.relatedVideoId }
    return fetched.map { track ->
        val prior = existingByRelatedId[track.videoId]
        RelatedGraphEdgeEntity(
            sourceVideoId = sourceVideoId,
            relatedVideoId = track.videoId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            durationSeconds = track.durationSeconds,
            seenCount = (prior?.seenCount ?: 0) + 1,
            firstSeenAtMillis = prior?.firstSeenAtMillis ?: nowMillis,
            lastSeenAtMillis = nowMillis,
        )
    }
}

@Singleton
class RelatedGraphRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val dao: RelatedGraphDao,
    private val scope: CoroutineScope,
) {
    private val inFlightRefreshes = ConcurrentHashMap<String, Deferred<Unit>>()
    private val hygieneMutex = Mutex()
    private val initialHygienePending = AtomicBoolean(true)

    suspend fun relatedFor(
        videoId: String,
        limit: Int = 30,
        prefetchStreams: Boolean = false,
        forceNetwork: Boolean = false,
    ): List<YouTubeMusicTrack> {
        if (videoId.isBlank() || limit <= 0) return emptyList()
        ensureInitialHygiene()
        val cached = dao.edgesFor(videoId, limit).map { it.toTrack() }
        if (forceNetwork) return awaitNetwork(videoId, limit, prefetchStreams) ?: cached
        if (cached.isEmpty()) return awaitNetwork(videoId, limit, prefetchStreams) ?: emptyList()
        val lastSeen = dao.lastSeenFor(videoId)
        val ageMs = System.currentTimeMillis() - (lastSeen ?: 0L)
        if (needsBackgroundRefresh(ageMs, cached.size, limit)) {
            scheduleBackgroundRefresh(videoId, limit, prefetchStreams)
        }
        return cached
    }

    suspend fun topReferredTracks(limit: Int = 30): List<YouTubeMusicTrack> {
        ensureInitialHygiene()
        val ranked = dao.topReferred(limit)
        if (ranked.isEmpty()) return emptyList()
        val mostRecentById = dao.snapshotsFor(ranked.map { it.relatedVideoId })
            .groupBy { it.relatedVideoId }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.lastSeenAtMillis } }
        return ranked
            .mapNotNull { mostRecentById[it.relatedVideoId] }
            .filter { it.title.isNotBlank() }
            .map { it.toTrack() }
    }

    internal suspend fun enforceHygiene(
        nowMillis: Long = System.currentTimeMillis(),
        maxEdges: Int = MAX_EDGES,
    ) {
        hygieneMutex.withLock {
            dao.pruneOlderThan(nowMillis - PRUNE_AFTER_MS)
            if (dao.count() > maxEdges) {
                dao.trimToCap(maxEdges)
            }
        }
    }

    private suspend fun ensureInitialHygiene() {
        if (initialHygienePending.compareAndSet(true, false)) {
            enforceHygiene()
        }
    }

    private suspend fun awaitNetwork(
        videoId: String,
        limit: Int,
        prefetchStreams: Boolean,
    ): List<YouTubeMusicTrack>? {
        val fetched = try {
            innerTube.fetchRelatedSongs(videoId, limit, prefetchStreams)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return null
        }
        persist(videoId, fetched)
        return fetched
    }

    private fun scheduleBackgroundRefresh(videoId: String, limit: Int, prefetchStreams: Boolean) {
        inFlightRefreshes.computeIfAbsent(videoId) {
            scope.async<Unit> {
                try {
                    val fetched = innerTube.fetchRelatedSongs(videoId, limit, prefetchStreams)
                    persist(videoId, fetched)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Log.d(TAG, "Related-graph background refresh failed for $videoId", error)
                }
            }.also { refresh ->
                refresh.invokeOnCompletion { inFlightRefreshes.remove(videoId, refresh) }
            }
        }
    }

    private suspend fun persist(sourceVideoId: String, fetched: List<YouTubeMusicTrack>) {
        if (fetched.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = dao.edgesFor(sourceVideoId, limit = Int.MAX_VALUE)
        val merged = mergeFetchedEdges(existing, fetched, sourceVideoId, now)
        if (merged.isEmpty()) return
        dao.upsertAll(merged)
        enforceHygiene(now)
    }

    private fun RelatedGraphEdgeEntity.toTrack() = YouTubeMusicTrack(
        videoId = relatedVideoId,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        durationSeconds = durationSeconds,
    )

    private companion object {
        const val TAG = "RelatedGraphRepository"
    }
}
