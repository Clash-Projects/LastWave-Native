package com.lastwave.app.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import java.io.File

/**
 * High-performance in-memory LRU cache for widget album art bitmaps (4 MiB max).
 * Prevents redundant Binder transactions and expensive BitmapFactory disk decodes
 * during Glance recomposition, enabling instant zero-lag UI updates and smooth animations.
 */
object AlbumArtBitmapCache {
    private const val MAX_CACHE_SIZE_KB = 4 * 1024 // 4 MiB

    private val lru = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun get(key: String): Bitmap? = synchronized(lru) {
        val bitmap = lru.get(key)
        if (bitmap != null && !bitmap.isRecycled) bitmap else null
    }

    fun put(key: String, bitmap: Bitmap) = synchronized(lru) {
        if (!bitmap.isRecycled) {
            lru.put(key, bitmap)
        }
    }

    fun remove(key: String) = synchronized(lru) {
        lru.remove(key)
    }

    fun getOrDecode(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        val modKey = "${path}_${file.lastModified()}"
        synchronized(lru) {
            val cached = lru.get(modKey)
            if (cached != null && !cached.isRecycled) return cached
        }
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        synchronized(lru) {
            lru.put(modKey, decoded)
            lru.put(path, decoded)
        }
        return decoded
    }

    fun clear() = synchronized(lru) {
        lru.evictAll()
    }
}
