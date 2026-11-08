package com.lastwave.app.data.local

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** LRU eviction whose limit follows the space currently used by artwork. */
@UnstableApi
class AdaptiveCacheEvictor(initialMaxBytes: Long) : CacheEvictor {
    private val maxBytes = AtomicLong(initialMaxBytes.coerceAtLeast(0L))
    private val cacheReference = AtomicReference<Cache?>(null)
    private val trimming = AtomicBoolean(false)

    fun setMaxBytes(bytes: Long) {
        val safeBytes = bytes.coerceAtLeast(0L)
        if (maxBytes.getAndSet(safeBytes) != safeBytes) trim()
    }

    /** Called after SimpleCache has initialized its index. */
    fun attach(cache: Cache) {
        cacheReference.set(cache)
        trim()
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        cacheReference.set(cache)
        // Reserve known incoming space so a large write cannot temporarily push
        // the combined pool well beyond its configured limit.
        val reserve = length.takeIf { it > 0L } ?: 0L
        trimTo((maxBytes.get() - reserve).coerceAtLeast(0L))
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        cacheReference.set(cache)
        trim()
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        cacheReference.set(cache)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        cacheReference.set(cache)
    }

    private fun trim() = trimTo(maxBytes.get())

    private fun trimTo(targetBytes: Long) {
        if (!trimming.compareAndSet(false, true)) return
        var removedAny = false
        val limitBeforeTrim = maxBytes.get()
        try {
            val cache = cacheReference.get() ?: return
            if (cache.cacheSpace > minOf(targetBytes, maxBytes.get())) {
                val oldestFirst = cache.keys.asSequence()
                    .flatMap { key -> cache.getCachedSpans(key).asSequence() }
                    .sortedBy(CacheSpan::lastTouchTimestamp)
                    .toList()
                for (span in oldestFirst) {
                    if (cache.cacheSpace <= minOf(targetBytes, maxBytes.get())) break
                    if (runCatching { cache.removeSpan(span) }.isSuccess) {
                        removedAny = true
                    }
                }
            }
        } finally {
            trimming.set(false)
        }
        // Retry only when this pass made progress; this catches a concurrent
        // limit reduction without spinning if every remaining span is locked.
        val cache = cacheReference.get()
        val limitAfterTrim = maxBytes.get()
        if (cache != null && cache.cacheSpace > limitAfterTrim &&
            (removedAny || limitAfterTrim != limitBeforeTrim)
        ) {
            trim()
        }
    }
}
