package com.lastwave.app.playback.resolve

import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.data.music.isCooperativeCancellation
import com.lastwave.app.data.music.resolutionFailureDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class StaleResolveException(val identity: TrackIdentity) : CancellationException(
    "Stale resolve for ${identity.cacheKey}",
)

class MismatchedStreamException(
    val requested: TrackIdentity,
    resolvedVideoId: String,
) : IOException(
    "Rejected mismatched stream requested=${requested.youtubeVideoId} resolved=$resolvedVideoId",
)

/**
 * Highest-priority resolver. One user selection at a time.
 * A result is applied only when its token still matches the selection that started it.
 */
class PlaybackResolver(
    private val cache: PlaybackStreamCache,
    private val logger: ResolverLogger,
) {
    private val generation = AtomicLong()
    private val gate = Any()

    @Volatile
    var requested: TrackIdentity? = null
        private set

    val token: Long get() = generation.get()

    fun begin(identity: TrackIdentity): Long = synchronized(gate) {
        if (requested == identity) return generation.get()
        requested = identity
        generation.incrementAndGet()
    }

    /**
     * Joins an in-flight resolve of [identity], or claims it.
     * A different active identity is stale and must not steal the generation.
     */
    fun claim(identity: TrackIdentity): Long = synchronized(gate) {
        val active = requested
        if (active != null && active != identity) throw StaleResolveException(identity)
        if (active == null) {
            requested = identity
            generation.incrementAndGet()
        }
        generation.get()
    }

    /** A rematch may replace its own selection, even if another tap arrives concurrently. */
    fun replaceClaim(previous: TrackIdentity, replacement: TrackIdentity) {
        synchronized(gate) {
            if (requested != previous) throw StaleResolveException(previous)
            requested = replacement
            generation.incrementAndGet()
        }
    }

    fun invalidate() {
        synchronized(gate) {
            generation.incrementAndGet()
            requested = null
        }
    }

    fun isCurrent(token: Long, identity: TrackIdentity): Boolean =
        token == generation.get() && requested == identity

    suspend fun resolve(
        token: Long,
        identity: TrackIdentity,
        durationMs: Long?,
        extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
    ): StreamCache {
        val started = System.nanoTime()
        logger.event(ResolveEvent.START, identity, 0L, durationMs, "playback")
        ensureCurrent(token, identity, started, durationMs)
        cache.get(identity)?.let { hit ->
            if (!hit.matches(identity)) {
                cache.invalidate(identity)
            } else {
                logger.event(
                    ResolveEvent.CACHE_HIT,
                    identity,
                    elapsedSince(started),
                    hit.durationMs ?: durationMs,
                    "playback",
                )
                ensureCurrent(token, identity, started, durationMs)
                return hit
            }
        }
        logger.event(ResolveEvent.CACHE_MISS, identity, elapsedSince(started), durationMs, "playback")
        var failure: Exception? = null
        repeat(2) {
            ensureCurrent(token, identity, started, durationMs)
            val extracted = try {
                PlaybackStartupLog.extractStart()
                try {
                    extract(identity.youtubeVideoId)
                } finally {
                    PlaybackStartupLog.extractEnd()
                }
            } catch (error: Throwable) {
                if (error.isCooperativeCancellation()) {
                    logger.event(ResolveEvent.CANCELLED, identity, elapsedSince(started), durationMs, "playback")
                    throw error
                }
                logger.event(
                    ResolveEvent.FAILED,
                    identity,
                    elapsedSince(started),
                    durationMs,
                    resolutionFailureDetail(error),
                )
                if (error is TimeoutCancellationException || error is TimeoutException) {
                    throw IOException("Timed out resolving ${identity.youtubeVideoId}", error)
                }
                throw error
            }
            if (extracted.videoId != identity.youtubeVideoId) {
                cache.invalidate(identity)
                failure = MismatchedStreamException(identity, extracted.videoId)
                logger.event(
                    ResolveEvent.IGNORED_STALE,
                    identity,
                    elapsedSince(started),
                    durationMs,
                    "resolvedTrackId=${extracted.videoId}",
                )
                return@repeat
            }
            val entry = extracted.toStreamCache(identity)
            // A skip can invalidate the token in the gap after the player
            // response returns. Keep the URL anyway so the next tap of this
            // video is a cache hit instead of another youtubei/player call.
            if (!isCurrent(token, identity)) {
                cache.putIfAbsent(entry)
                logger.event(
                    ResolveEvent.IGNORED_STALE,
                    identity,
                    elapsedSince(started),
                    durationMs,
                    "cached-after-complete",
                )
                throw StaleResolveException(identity)
            }
            publish(token, identity, entry, started, durationMs)
            logger.event(
                ResolveEvent.SUCCESS,
                identity,
                elapsedSince(started),
                entry.durationMs ?: durationMs,
                "playback",
            )
            return entry
        }
        throw failure ?: IOException("Unable to resolve ${identity.youtubeVideoId}")
    }

    private fun publish(
        token: Long,
        identity: TrackIdentity,
        entry: StreamCache,
        started: Long,
        durationMs: Long?,
    ) {
        synchronized(gate) {
            if (!isCurrent(token, identity)) {
                logger.event(
                    ResolveEvent.IGNORED_STALE,
                    identity,
                    elapsedSince(started),
                    durationMs,
                    "requestedTrackId=${requested?.trackId.orEmpty()}",
                )
                throw StaleResolveException(identity)
            }
            cache.putFromPlayback(entry)
        }
    }

    private fun ensureCurrent(
        token: Long,
        identity: TrackIdentity,
        started: Long,
        durationMs: Long?,
    ) {
        if (isCurrent(token, identity)) return
        logger.event(
            ResolveEvent.IGNORED_STALE,
            identity,
            elapsedSince(started),
            durationMs,
            "requestedTrackId=${requested?.trackId.orEmpty()}",
        )
        throw StaleResolveException(identity)
    }
}
