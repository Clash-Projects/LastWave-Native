package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Serializable
private data class SimpResponse(
    val success: Boolean = false,
    val data: List<SimpTrack>? = null,
)

@Serializable
private data class SimpTrack(
    val duration: Int? = null,
    val richSyncLyrics: String? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
)

/**
 * Community database keyed on the playing video id, so the exact cut that is
 * playing is looked up instead of a same-name edit. Rich sync is enhanced LRC
 * with per-word stamps; plain LRC is the fallback.
 */
@Singleton
class SimpMusicLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetchLyrics(
        videoId: String?,
        durationSeconds: Int? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (videoId.isNullOrBlank()) return@withContext null
        val request = Request.Builder()
            .url("https://api-lyrics.simpmusic.org/v1/${videoId.trim()}")
            .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return@withContext null
            val response = runCatching { json.decodeFromString<SimpResponse>(body) }.getOrNull()
                ?: return@withContext null
            if (!response.success) return@withContext null
            val seconds = durationSeconds?.takeIf { it > 0 } ?: 0
            val track = response.data.orEmpty()
                .filter { seconds <= 0 || abs((it.duration ?: 0) - seconds) <= DURATION_TOLERANCE_SECONDS }
                .minByOrNull { abs((it.duration ?: 0) - seconds) }
                ?: return@withContext null
            track.richSyncLyrics?.takeIf { it.isNotBlank() }
                ?.let { LyricsRepository.parseEnhancedLrc(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: track.syncedLyrics?.takeIf { it.isNotBlank() }
                    ?.let { LyricsRepository.parseLrc(it) }
                    ?.takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val DURATION_TOLERANCE_SECONDS = 10
    }
}
