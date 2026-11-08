package com.lastwave.app.data.local

import java.util.concurrent.atomic.AtomicInteger

/** Shared budget for the two independently managed disk caches. */
object CacheStoragePolicy {
    const val MEGABYTE_BYTES = 1_000_000L
    const val DEFAULT_LIMIT_MB = 320
    const val MIN_LIMIT_MB = 128
    const val MAX_LIMIT_MB = 2048
    const val STREAM_CACHE_BYTES = 64L * 1024 * 1024

    private val configuredLimitMb = AtomicInteger(DEFAULT_LIMIT_MB)

    fun setLimitMb(limitMb: Int) {
        configuredLimitMb.set(limitMb.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB))
    }

    fun imageCacheBytes(lowRamDevice: Boolean): Long {
        val totalBytes = configuredLimitMb.get().toLong() * MEGABYTE_BYTES
        val availableBytes = (totalBytes - STREAM_CACHE_BYTES).coerceAtLeast(0L)
        return if (lowRamDevice) minOf(availableBytes, 96L * 1024 * 1024) else availableBytes
    }
}
