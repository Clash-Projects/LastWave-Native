package com.lastwave.app.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheStoragePolicyTest {
    @After
    fun restoreDefaultLimit() {
        CacheStoragePolicy.setLimitMb(CacheStoragePolicy.DEFAULT_LIMIT_MB)
        CacheStoragePolicy.setArtworkCacheBytes(0L)
    }

    @Test
    fun imageCacheCanUseTheWholePoolWhileAudioGetsTheRemainder() {
        val expectedBytes = CacheStoragePolicy.DEFAULT_LIMIT_MB * CacheStoragePolicy.MEGABYTE_BYTES

        assertEquals(expectedBytes, CacheStoragePolicy.imageCacheBytes(lowRamDevice = false))
        assertEquals(expectedBytes, CacheStoragePolicy.streamCacheLimitBytes())
    }

    @Test
    fun artworkUsageReducesTheAudioShareOfThePool() {
        CacheStoragePolicy.setLimitMb(512)
        CacheStoragePolicy.setArtworkCacheBytes(180L * CacheStoragePolicy.MEGABYTE_BYTES)

        assertEquals(
            332L * CacheStoragePolicy.MEGABYTE_BYTES,
            CacheStoragePolicy.streamCacheLimitBytes(),
        )
    }

    @Test
    fun lowRamImageBudgetIsCapped() {
        CacheStoragePolicy.setLimitMb(CacheStoragePolicy.MAX_LIMIT_MB)

        assertEquals(96L * MEBIBYTE, CacheStoragePolicy.imageCacheBytes(lowRamDevice = true))
    }

    private companion object {
        const val MEBIBYTE = 1024L * 1024L
    }
}
