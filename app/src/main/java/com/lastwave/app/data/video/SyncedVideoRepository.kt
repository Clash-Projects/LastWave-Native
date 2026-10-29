package com.lastwave.app.data.video

import android.util.Log
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.SyncedVideoStreamBundle
import com.lastwave.app.data.music.YouTubeStreamExtractor
import com.lastwave.app.data.music.YouTubeVideoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncedVideoRepo"
private const val CACHE_EXPIRY_MS = 3 * 60 * 60 * 1000L // 3 hours

/**
 * Resolves and caches direct video streams for synchronized music video playback.
 */
@Singleton
class SyncedVideoRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val streamExtractor: YouTubeStreamExtractor,
) {
    private val cache = ConcurrentHashMap<String, CachedVideoBundle>()

    private data class CachedVideoBundle(
        val bundle: SyncedVideoStreamBundle,
        val expiresAtMs: Long,
    )

    suspend fun resolveVideo(
        trackTitle: String,
        trackArtist: String,
        videoId: String?,
    ): Result<SyncedVideoStreamBundle> = withContext(Dispatchers.IO) {
        runCatching {
            var targetVideoId = videoId?.trim()
            if (targetVideoId.isNullOrBlank()) {
                targetVideoId = findVideoId(trackTitle, trackArtist)
                    ?: throw IllegalStateException("Could not find video for $trackTitle — $trackArtist")
            }

            val now = System.currentTimeMillis()
            cache[targetVideoId]?.let { cached ->
                if (cached.expiresAtMs > now) {
                    return@runCatching cached.bundle
                } else {
                    cache.remove(targetVideoId)
                }
            }

            val bundle = try {
                streamExtractor.resolveVideoStreamBundle(targetVideoId)
            } catch (e: Exception) {
                // If resolving direct videoId failed (e.g. audio-only topic track),
                // search for the official music video specifically
                val fallbackId = findVideoId(trackTitle, trackArtist, excludeId = targetVideoId)
                if (!fallbackId.isNullOrBlank()) {
                    streamExtractor.resolveVideoStreamBundle(fallbackId).also {
                        targetVideoId = fallbackId
                    }
                } else {
                    throw e
                }
            }
            val finalVideoId = targetVideoId ?: throw IllegalStateException("Could not find video for $trackTitle — $trackArtist")
            cache[finalVideoId] = CachedVideoBundle(
                bundle = bundle,
                expiresAtMs = now + CACHE_EXPIRY_MS,
            )
            bundle
        }.onFailure { error ->
            Log.w(TAG, "Failed to resolve video stream for $trackTitle: ${error.message}", error)
        }
    }

    suspend fun findVideoId(
        trackTitle: String,
        trackArtist: String,
        excludeId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val query = "$trackTitle $trackArtist official video"
        val searchResults = runCatching {
            innerTube.searchSongs(query, limit = 6)
        }.getOrNull()
        searchResults?.firstOrNull { it.videoId != excludeId && it.videoId.isNotBlank() }?.videoId
    }
}
