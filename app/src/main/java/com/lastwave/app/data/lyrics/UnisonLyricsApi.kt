package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class UnisonResponse(
    val success: Boolean? = null,
    val data: UnisonEntry? = null,
)

@Serializable
internal data class UnisonEntry(
    val lyrics: String? = null,
    /** `ttml` or `lrc`. */
    val format: String? = null,
    /** `wordsync`, `linesync` or `plain`. */
    val syncType: String? = null,
)

/**
 * Unison (https://unison.boidu.dev): a community-submitted lyrics database,
 * the only source here whose contents are contributed rather than licensed.
 * Coverage is thin and uneven, but it occasionally has a track none of the
 * licensed catalogues do.
 *
 * Three shapes come back and the entry says which: Apple-style TTML, ordinary
 * line-stamped LRC (word stamps recognized when present), or plain text with
 * no timing at all — which is dropped here, since the plain-text fallbacks
 * sit later in the chain anyway.
 */
@Singleton
class UnisonLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        val builder = "https://unison.boidu.dev/lyrics".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext null
        builder.addQueryParameter("song", title.trim())
        builder.addQueryParameter("artist", artist.trim())
        if (!album.isNullOrBlank()) builder.addQueryParameter("album", album.trim())
        val seconds = durationSeconds?.takeIf { it > 0 }
        if (seconds != null) builder.addQueryParameter("duration", seconds.toString())
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return@withContext null
            val response = runCatching { json.decodeFromString<UnisonResponse>(body) }.getOrNull()
                ?: return@withContext null
            if (response.success != true) return@withContext null
            val entry = response.data ?: return@withContext null
            val text = entry.lyrics?.takeIf { it.isNotBlank() } ?: return@withContext null
            parse(text, entry.format, entry.syncType)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** The three shapes, told apart by what the entry says it is. */
    internal fun parse(text: String, format: String?, syncType: String?): List<LyricLine>? {
        val lines = when {
            format.equals(TTML, ignoreCase = true) -> TtmlParser.parse(text)
            syncType.equals(PLAIN, ignoreCase = true) -> emptyList()
            // Word stamps first: the format field says only "lrc", and an
            // enhanced file is still one. An empty enhanced parse is the
            // signal to fall through rather than a failure.
            else -> LyricsRepository.parseEnhancedLrc(text).takeIf { it.isNotEmpty() }
                ?: LyricsRepository.parseLrc(text)
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TTML = "ttml"
        private const val PLAIN = "plain"
        private const val USER_AGENT = "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)"
    }
}
