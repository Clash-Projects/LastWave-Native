package com.lastwave.app.data.canvas

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache for canvas clips — looping video clips played over cover art.
 *
 * A 5-second clip behind a 4-minute track loops ~50 times. Without caching,
 * ExoPlayer consumes each buffer and re-requests bytes from the network at position 0,
 * causing 50 downloads of the same file.
 *
 * Wrapping the upstream in [CacheDataSource] ensures only the first loop reaches the network;
 * all subsequent loops and re-plays stream directly from disk.
 */
@OptIn(UnstableApi::class)
object CanvasCache {

    private const val CACHE_LIMIT_BYTES = 150L * 1024 * 1024 // 150 MB

    @Volatile
    private var cache: SimpleCache? = null

    /**
     * Initializes the cache directory and database provider once per process.
     */
    @Synchronized
    fun init(context: Context) {
        if (cache != null) return
        val cacheDir = File(context.cacheDir, "canvas")
        val evictor = LeastRecentlyUsedCacheEvictor(CACHE_LIMIT_BYTES)
        val dbProvider = StandaloneDatabaseProvider(context)
        cache = SimpleCache(cacheDir, evictor, dbProvider)
    }

    /**
     * Wraps an [upstream] DataSource.Factory in a caching data source.
     */
    fun dataSourceFactory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        init(context)
        val activeCache = cache ?: return upstream
        return CacheDataSource.Factory()
            .setCache(activeCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
