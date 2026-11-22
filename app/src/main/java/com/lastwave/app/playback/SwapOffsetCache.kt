package com.lastwave.app.playback

/**
 * Identity of one source-master pair whose timing offset was measured.
 *
 * [currentSource] and [candidateSource] are the stable stream identities
 * (provider/track/format), never the signed URL: the URL rotates while the
 * master stays the same. [durationBucketSec] guards against reusing an offset
 * measured for another edit of the track.
 */
internal data class SwapOffsetKey(
    val trackKey: String,
    val currentSource: String,
    val candidateSource: String,
    val durationBucketSec: Int?,
) {
    fun reversed(): SwapOffsetKey = SwapOffsetKey(
        trackKey = trackKey,
        currentSource = candidateSource,
        candidateSource = currentSource,
        durationBucketSec = durationBucketSec,
    )
}

/**
 * In-memory cache of measured source offsets.
 *
 * Measuring an offset costs a pair of silent decoders plus a cross-correlation,
 * which is most of the remaining latency on a repeat upgrade/downgrade of the
 * same source pair. Only high-confidence measurements are stored; a low
 * confidence probe is repeated rather than trusted.
 *
 * Entries are session-scoped and expire after [ttlMs]. The reverse pair is
 * served by negating the stored lag, so an upgrade followed by a downgrade
 * reuses the same measurement.
 */
internal class SwapOffsetCache(
    private val maxEntries: Int = 64,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private class Entry(
        val lagMs: Long,
        val confidence: Double,
        val storedAtMs: Long,
    )

    private val lock = Any()
    private val entries = object : java.util.LinkedHashMap<SwapOffsetKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SwapOffsetKey, Entry>): Boolean =
            size > maxEntries
    }

    fun get(key: SwapOffsetKey): Long? = synchronized(lock) {
        entries[key]?.let { entry ->
            if (isFresh(entry)) return entry.lagMs
            entries.remove(key)
        }
        entries[key.reversed()]?.let { entry ->
            if (isFresh(entry)) return -entry.lagMs
            entries.remove(key.reversed())
        }
        null
    }

    fun put(key: SwapOffsetKey, lagMs: Long, confidence: Double) {
        if (confidence < MIN_CACHE_CONFIDENCE) return
        synchronized(lock) {
            entries[key] = Entry(lagMs, confidence, nowMs())
            entries.entries.removeIf { !isFresh(it.value) }
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    private fun isFresh(entry: Entry): Boolean = nowMs() - entry.storedAtMs <= ttlMs

    internal companion object {
        /** Above the probe's acceptance floor: only stable matches are reusable. */
        const val MIN_CACHE_CONFIDENCE = 0.60
        const val DEFAULT_TTL_MS = 2 * 60 * 60 * 1000L
    }
}
