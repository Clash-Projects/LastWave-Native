package com.lastwave.app.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheStoragePolicyTest {
    @After
    fun restoreDefaultLimit() {
        CacheStoragePolicy.setLimitMb(CacheStoragePolicy.DEFAULT_LIMIT_MB)
    }

    @Test
    fun defaultImageAndStreamBudgetsAddUpToTheSelectedLimit() {
        val expectedBytes = CacheStoragePolicy.DEFAULT_LIMIT_MB * CacheStoragePolicy.MEGABYTE_BYTES

        assertEquals(
            expectedBytes,
            CacheStoragePolicy.imageCacheBytes(lowRamDevice = false) + CacheStoragePolicy.STREAM_CACHE_BYTES,
        )
    }

    @Test
    fun selectedLimitUpdatesTheCombinedBudget() {
        CacheStoragePolicy.setLimitMb(512)

        assertEquals(
            512L * CacheStoragePolicy.MEGABYTE_BYTES,
            CacheStoragePolicy.imageCacheBytes(lowRamDevice = false) + CacheStoragePolicy.STREAM_CACHE_BYTES,
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
