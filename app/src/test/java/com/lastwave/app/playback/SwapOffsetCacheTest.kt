package com.lastwave.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwapOffsetCacheTest {
    private var nowMs = 1_000L

    private fun cache(maxEntries: Int = 8, ttlMs: Long = 10_000L) =
        SwapOffsetCache(maxEntries = maxEntries, ttlMs = ttlMs, nowMs = { nowMs })

    private fun key(
        track: String = "track",
        current: String = "youtube|opus",
        candidate: String = "lossless|hi-res",
        bucket: Int? = 200,
    ) = SwapOffsetKey(track, current, candidate, bucket)

    @Test
    fun `stores and returns a confident measurement`() {
        val cache = cache()
        cache.put(key(), lagMs = 137L, confidence = 0.9)

        assertEquals(137L, cache.get(key()))
    }

    @Test
    fun `reverse source pair returns the negated offset`() {
        val cache = cache()
        cache.put(key(current = "a", candidate = "b"), lagMs = 137L, confidence = 0.9)

        assertEquals(-137L, cache.get(key(current = "b", candidate = "a")))
    }

    @Test
    fun `low confidence is not cached`() {
        val cache = cache()
        cache.put(key(), lagMs = 137L, confidence = SwapOffsetCache.MIN_CACHE_CONFIDENCE - 0.01)

        assertNull(cache.get(key()))
        assertEquals(0, cache.size())
    }

    @Test
    fun `a different duration bucket is a different master`() {
        val cache = cache()
        cache.put(key(bucket = 200), lagMs = 137L, confidence = 0.9)

        assertNull(cache.get(key(bucket = 201)))
    }

    @Test
    fun `an entry expires`() {
        val cache = cache(ttlMs = 5_000L)
        cache.put(key(), lagMs = 137L, confidence = 0.9)
        nowMs += 5_001L

        assertNull(cache.get(key()))
    }

    @Test
    fun `the oldest entry is evicted at capacity`() {
        val cache = cache(maxEntries = 2)
        cache.put(key(track = "one"), lagMs = 1L, confidence = 0.9)
        cache.put(key(track = "two"), lagMs = 2L, confidence = 0.9)
        cache.put(key(track = "three"), lagMs = 3L, confidence = 0.9)

        assertEquals(2, cache.size())
        assertNull(cache.get(key(track = "one")))
        assertEquals(2L, cache.get(key(track = "two")))
    }
}
