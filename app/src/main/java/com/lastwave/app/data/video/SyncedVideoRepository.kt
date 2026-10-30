package com.lastwave.app.data.video

import android.util.Log
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.SyncedVideoStreamBundle
import com.lastwave.app.data.music.YouTubeStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncedVideoRepo"
private const val CACHE_EXPIRY_MS = 3 * 60 * 60 * 1000L // 3 hours

/**
 * Resolves and caches direct video streams for synchronized music video playback.
 * Implements a 2-tier pipeline:
 *  Tier 1: Innertube /next counterpart lookup (official YouTube Music OMV link)
 *  Tier 2: Verified Innertube search fallback with candidate scoring
 * Plus SponsorBlock API offset integration for frame-accurate music intro synchronization.
 */
@Singleton
class SyncedVideoRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val streamExtractor: YouTubeStreamExtractor,
    private val http: OkHttpClient,
) {
    private val cache = ConcurrentHashMap<String, CachedVideoBundle>()

    private data class CachedVideoBundle(
        val bundle: SyncedVideoStreamBundle,
        val expiresAtMs: Long,
    )

    fun invalidate(videoId: String?) {
        if (!videoId.isNullOrBlank()) {
            cache.remove(videoId)
        }
    }

    suspend fun resolveVideo(
        trackTitle: String,
        trackArtist: String,
        videoId: String?,
        expectedDurationMs: Long? = null,
    ): Result<SyncedVideoStreamBundle> = withContext(Dispatchers.IO) {
        runCatching {
            // Find the Official Music Video (OMV) ID
            val targetVideoId = findOfficialVideoId(
                trackTitle = trackTitle,
                trackArtist = trackArtist,
                audioTrackVideoId = videoId,
                expectedDurationMs = expectedDurationMs,
            )
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

            // Fetch SponsorBlock intro skit offset (e.g. 42.5s for Starboy)
            val introOffsetMs = fetchIntroOffsetMs(targetVideoId)

            val rawBundle = try {
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

            val bundle = rawBundle.copy(
                introOffsetMs = introOffsetMs,
                isDirectOmv = (targetVideoId != videoId),
            )

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
        expectedDurationMs: Long? = null,
    ): String? = withContext(Dispatchers.IO) {
        // Tier 1: Native Innertube next endpoint counterpart lookup
        if (!audioTrackVideoId.isNullOrBlank()) {
            val counterpartId = runCatching {
                innerTube.findCounterpartVideoId(audioTrackVideoId)
            }.getOrNull()
            if (!counterpartId.isNullOrBlank() && counterpartId != audioTrackVideoId) {
                Log.i(TAG, "Found Tier 1 official counterpart OMV for '$trackTitle': $counterpartId")
                return@withContext counterpartId
            }
        }

        // Tier 2: Verified Innertube search fallback
        val cleanTitle = cleanTrackTitle(trackTitle)
        val cleanArtist = trackArtist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty()
        val queries = listOfNotNull(
            "$cleanArtist - $cleanTitle Official Music Video".trim().ifBlank { null },
            "$cleanTitle $cleanArtist official video".trim().ifBlank { null },
            "$cleanTitle $cleanArtist".trim().ifBlank { null },
        )

        for (query in queries) {
            val videoResults = runCatching {
                innerTube.searchVideos(query, limit = 8)
            }.getOrNull().orEmpty()

            val candidates = videoResults.filter { it.videoId.isNotBlank() }
            if (candidates.isEmpty()) continue

            // Score candidates to guarantee the best official music video
            val scored = candidates.map { track ->
                var score = 0
                val t = track.title.lowercase()
                val a = track.artist.lowercase()
                val queryArtist = cleanArtist.lowercase()
                val queryTitle = cleanTitle.lowercase()

                if (t.contains(queryTitle)) score += 40
                if (t.contains("official music video") || t.contains("official video")) score += 50
                else if (t.contains("music video") || t.contains("official visualizer")) score += 30
                if (t.contains("mv") || t.contains("m/v")) score += 25

                if (queryArtist.isNotBlank()) {
                    if (a.contains(queryArtist) || queryArtist.contains(a)) score += 30
                    if (t.contains(queryArtist)) score += 15
                }
                if (a.contains("vevo") || t.contains("vevo")) score += 35
                if (a.contains("official") || a.contains("records") || a.contains("entertainment")) score += 15

                // Penalties for unwanted videos
                if (t.contains("lyric") || t.contains("lyrics")) score -= 30
                if (t.contains("audio") || t.contains("topic")) score -= 40
                if (t.contains("live") && !queryTitle.contains("live")) score -= 50
                if (t.contains("cover") || t.contains("karaoke") || t.contains("reaction")) score -= 80
                if (t.contains("remix") && !queryTitle.contains("remix")) score -= 70
                if (t.contains("mashup") || t.contains("parody") || t.contains("tribute")) score -= 90
                if (t.contains("slowed") || t.contains("reverb") || t.contains("sped up") || t.contains("speed up") || t.contains("8d")) score -= 80
                if (t.contains("teaser") || t.contains("trailer") || t.contains("preview") || t.contains("bts") || t.contains("behind the scenes")) score -= 100
                if (t.contains("short") || t.contains("shorts") || t.contains("#shorts")) score -= 100

                // Duration comparison to reject trailers, extended short films, or wrong length uploads
                if (expectedDurationMs != null && expectedDurationMs > 30_000L) {
                    val expectedSec = (expectedDurationMs / 1000L).toInt()
                    val candidateSec = track.durationSeconds
                    if (candidateSec != null && candidateSec > 0) {
                        val delta = kotlin.math.abs(candidateSec - expectedSec)
                        when {
                            delta <= 15 -> score += 35
                            delta <= 45 -> score += 15
                            delta > 120 -> score -= 80
                            delta > 60 -> score -= 40
                        }
                        if (candidateSec < 60 && expectedSec > 90) {
                            score -= 120
                        }
                    }
                }

                if (track.videoId == audioTrackVideoId) {
                    score += 10
                }

                track to score
            }.sortedByDescending { it.second }

            val best = scored.firstOrNull { it.second >= 30 }?.first
            if (best != null) {
                Log.i(TAG, "Found Tier 2 official video for '$trackTitle': ${best.videoId} (${best.title}) score=${scored.first().second}")
                return@withContext best.videoId
            }
        }
        null
    }

    suspend fun findVideoId(
        trackTitle: String,
        trackArtist: String,
        excludeId: String? = null,
        expectedDurationMs: Long? = null,
    ): String? = findOfficialVideoId(trackTitle, trackArtist, audioTrackVideoId = excludeId, expectedDurationMs = expectedDurationMs)

    suspend fun fetchIntroOffsetMs(videoId: String): Long = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext 0L
        runCatching {
            val request = okhttp3.Request.Builder()
                .url("https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&category=music_offtopic")
                .header("User-Agent", "LastWave-Native/1.0")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching 0L
                val body = response.body?.string() ?: return@runCatching 0L
                val array = org.json.JSONArray(body)
                for (i in 0 until array.length()) {
                    val seg = array.optJSONObject(i) ?: continue
                    val segment = seg.optJSONArray("segment") ?: continue
                    val start = segment.optDouble(0, 0.0)
                    val end = segment.optDouble(1, 0.0)
                    if (start <= 2.5 && end > start) {
                        return@runCatching ((end - start) * 1000.0).toLong()
                    }
                }
                0L
            }
        }.getOrDefault(0L)
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(with.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[feat\..*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*remaster(?:ed)?(?:\s*\d{4})?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(official\s*(?:music\s*)?video\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[official\s*(?:music\s*)?video\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(official\s*audio\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[official\s*audio\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(visualizer\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[visualizer\]""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
