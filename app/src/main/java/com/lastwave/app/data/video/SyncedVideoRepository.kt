package com.lastwave.app.data.video

import android.util.Log
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.SyncedVideoStreamBundle
import com.lastwave.app.data.music.YouTubeStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncedVideoRepo"
private const val CACHE_EXPIRY_MS = 3 * 60 * 60 * 1000L // 3 hours

/**
 * Resolves and caches direct video streams for synchronized music video playback.
 * Searches explicitly for official music videos (OMV) rather than static Art Tracks (ATV).
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
            // Priority 1: Find the ACTUAL Official Music Video (OMV).
            // Tracks in the player are almost always ATV (Audio Track Version / Art Tracks),
            // which are static album cover thumbnails on YouTube. We find the real music video!
            val targetVideoId = findOfficialVideoId(trackTitle, trackArtist, audioTrackVideoId = videoId)
                ?: videoId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Could not find music video for $trackTitle — $trackArtist")

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
                // If official video extraction failed and we had a different audioTrackVideoId,
                // try that as fallback
                if (!videoId.isNullOrBlank() && videoId != targetVideoId) {
                    streamExtractor.resolveVideoStreamBundle(videoId)
                } else {
                    throw e
                }
            }
            cache[targetVideoId] = CachedVideoBundle(
                bundle = bundle,
                expiresAtMs = now + CACHE_EXPIRY_MS,
            )
            bundle
        }.onFailure { error ->
            Log.w(TAG, "Failed to resolve video stream for $trackTitle: ${error.message}", error)
        }
    }

    suspend fun findOfficialVideoId(
        trackTitle: String,
        trackArtist: String,
        audioTrackVideoId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(trackTitle)
        val cleanArtist = trackArtist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty()
        val queries = listOfNotNull(
            "$cleanTitle $cleanArtist official video".trim().ifBlank { null },
            "$cleanTitle $cleanArtist official music video".trim().ifBlank { null },
            "$cleanTitle $cleanArtist".trim().ifBlank { null },
        )

        for (query in queries) {
            val videoResults = runCatching {
                innerTube.searchVideos(query, limit = 8)
            }.getOrNull().orEmpty()

            val candidates = videoResults.filter { it.videoId != audioTrackVideoId && it.videoId.isNotBlank() }
            if (candidates.isEmpty()) continue

            // Score candidates to guarantee the best official music video
            val scored = candidates.map { track ->
                var score = 0
                val t = track.title.lowercase()
                val a = track.artist.lowercase()
                val queryArtist = cleanArtist.lowercase()
                val queryTitle = cleanTitle.lowercase()

                if (t.contains(queryTitle)) score += 40
                if (t.contains("official video") || t.contains("official music video")) score += 50
                if (t.contains("mv") || t.contains("m/v")) score += 30
                if (queryArtist.isNotBlank() && (a.contains(queryArtist) || queryArtist.contains(a))) score += 20
                if (t.contains("lyric") || t.contains("lyrics")) score -= 30
                if (t.contains("audio") || t.contains("topic")) score -= 50
                if (t.contains("live") && !queryTitle.contains("live")) score -= 40
                if (t.contains("visualizer") || t.contains("visualiser")) score -= 20
                track to score
            }.sortedByDescending { it.second }

            val best = scored.firstOrNull { it.second > -40 }?.first ?: candidates.first()
            return@withContext best.videoId
        }
        null
    }

    suspend fun findVideoId(
        trackTitle: String,
        trackArtist: String,
        excludeId: String? = null,
    ): String? = findOfficialVideoId(trackTitle, trackArtist, audioTrackVideoId = excludeId)

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(with.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[feat\..*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*remaster(?:ed)?(?:\s*\d{4})?""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
