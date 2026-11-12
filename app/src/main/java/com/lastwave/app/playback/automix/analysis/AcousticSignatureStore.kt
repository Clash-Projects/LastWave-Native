package com.lastwave.app.playback.automix.analysis

import android.util.LruCache
import com.lastwave.app.playback.automix.api.CamelotKey
import com.lastwave.app.playback.automix.api.DecibelLufs
import javax.inject.Inject
import javax.inject.Singleton

data class AcousticSignature(
    val trackId: String,
    val durationMs: Long,
    val sourceHash: String,
    val camelotKey: CamelotKey,
    val beatGrid: BeatGridEstimate,
    val introVocalMask: VocalActivityMask,
    val outroVocalMask: VocalActivityMask,
    val integratedLufs: DecibelLufs,
    val analysisTimestampMs: Long = System.currentTimeMillis(),
)

@Singleton
class AcousticSignatureStore @Inject constructor() {

    // In-memory LRU cache of acoustic signatures keyed by cacheKey
    private val memoryCache = object : LruCache<String, AcousticSignature>(100) {}

    fun getSignature(trackId: String, durationMs: Long, sourceHash: String): AcousticSignature? {
        val key = buildKey(trackId, durationMs, sourceHash)
        synchronized(memoryCache) {
            return memoryCache.get(key)
        }
    }

    fun putSignature(signature: AcousticSignature) {
        val key = buildKey(signature.trackId, signature.durationMs, signature.sourceHash)
        synchronized(memoryCache) {
            memoryCache.put(key, signature)
        }
    }

    fun clear() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
    }

    private fun buildKey(trackId: String, durationMs: Long, sourceHash: String): String {
        return "$trackId:$durationMs:$sourceHash"
    }
}
