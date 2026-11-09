package com.lastwave.app.data.local

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Coordinates the combined on-disk budget for artwork and streamed audio. */
object CacheStoragePolicy {
    const val MEGABYTE_BYTES = 1_000_000L
    const val DEFAULT_LIMIT_MB = 320
    const val MIN_LIMIT_MB = 128
    const val MAX_LIMIT_MB = 4000
    private val configuredLimitMb = AtomicInteger(DEFAULT_LIMIT_MB)
    private val artworkBytes = AtomicLong(0L)
    private val streamLimitListener = AtomicReference<((Long) -> Unit)?>(null)

    fun setLimitMb(limitMb: Int) {
        val safeLimit = limitMb.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
        if (configuredLimitMb.getAndSet(safeLimit) != safeLimit) notifyStreamLimitChanged()
    }

    fun totalLimitBytes(): Long = configuredLimitMb.get().toLong() * MEGABYTE_BYTES

    /** The image cache may use the full pool, subject to a tighter low-RAM cap. */
    fun imageCacheBytes(lowRamDevice: Boolean): Long {
        val totalBytes = totalLimitBytes()
        return if (lowRamDevice) minOf(totalBytes, LOW_RAM_IMAGE_CACHE_BYTES) else totalBytes
    }

    fun streamCacheLimitBytes(): Long = (totalLimitBytes() - artworkBytes.get()).coerceAtLeast(0L)

    fun setArtworkCacheBytes(bytes: Long) {
        val safeBytes = bytes.coerceAtLeast(0L)
        if (artworkBytes.getAndSet(safeBytes) != safeBytes) notifyStreamLimitChanged()
    }

    /** Registers the single playback cache and immediately gives it its current share. */
    fun setStreamLimitListener(listener: ((Long) -> Unit)?) {
        streamLimitListener.set(listener)
        listener?.invoke(streamCacheLimitBytes())
    }

    private fun notifyStreamLimitChanged() {
        streamLimitListener.get()?.invoke(streamCacheLimitBytes())
    }

    private const val LOW_RAM_IMAGE_CACHE_BYTES = 96L * 1024 * 1024
}
