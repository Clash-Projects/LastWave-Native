package com.lastwave.app.data.lyrics

import com.lastwave.app.BuildConfig
import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import com.lastwave.app.data.network.ITunesSearchClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class LastWaveLyricsResponse(
    val type: String? = null,
    val content: String? = null,
    val track: String? = null,
    val source: String? = null,
)

/**
 * Syllable and line-synced Apple lyrics provided by LastWave backend.
 *
 * Configured via GitHub Actions secrets / BuildConfig:
 * - `LASTWAVE_LYRICS_TOKEN`: Access token passed via `X-Lyrics-Token` header.
 * - `LASTWAVE_LYRICS_URL`: Base backend URL.
 */
@Singleton
class LastWaveLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val iTunesSearchClient: ITunesSearchClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val baseUrl: String
        get() = BuildConfig.LASTWAVE_LYRICS_URL.trimEnd('/')

    private val token: String
        get() = BuildConfig.LASTWAVE_LYRICS_TOKEN

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        isrc: String? = null,
    ): LyricsResult.Success? {
        return fetchLyricsWithIsrc(title, artist, album, durationSeconds, isrc)?.second
    }

    suspend fun fetchLyricsWithIsrc(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        isrc: String? = null,
    ): Pair<String?, LyricsResult.Success>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || token.isBlank() || baseUrl.isBlank()) return@withContext null

        val song = iTunesSearchClient.searchBestMatch(title, artist, durationSeconds)
            ?: return@withContext null

        val result = fetchLyricsForId(
            id = song.trackId.toString(),
            isrc = isrc,
            title = song.trackName ?: title,
            artist = song.artistName ?: artist,
        ) ?: return@withContext null

        Pair(isrc, result)
    }

    suspend fun fetchLyricsForId(
        id: String,
        isrc: String? = null,
        title: String? = null,
        artist: String? = null,
    ): LyricsResult.Success? = withContext(Dispatchers.IO) {
        if (id.isBlank() || token.isBlank() || baseUrl.isBlank()) return@withContext null
        val urlBuilder = "$baseUrl/lyrics".toHttpUrlOrNull()?.newBuilder() ?: return@withContext null
        urlBuilder.addQueryParameter("id", id)
        if (!isrc.isNullOrBlank()) {
            urlBuilder.addQueryParameter("isrc", isrc)
        }
        if (!title.isNullOrBlank()) {
            urlBuilder.addQueryParameter("title", title)
        }
        if (!artist.isNullOrBlank()) {
            urlBuilder.addQueryParameter("artist", artist)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("X-Lyrics-Token", token)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val body = try {
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return@withContext null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withContext null
        }

        val trimmed = body.trim()
        if (trimmed.isEmpty()) return@withContext null

        // 1. Direct or embedded TTML
        val ttml = extractTtml(trimmed) ?: trimmed.takeIf { "<p" in it.lowercase() }
        if (!ttml.isNullOrBlank() && "<p" in ttml.lowercase()) {
            val ttmlLines = TtmlParser.parse(ttml)
            if (ttmlLines.isNotEmpty()) {
                val hasWordTiming = ttmlLines.any { it.hasSyllables }
                return@withContext LyricsResult.Success(
                    lines = ttmlLines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = ttmlLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = if (hasWordTiming) "LastWave (Word-Sync)" else "LastWave (Line-Sync)",
                )
            }
        }

        // 2. Parse JSON response envelope for LRC or plain text
        val response = try {
            json.decodeFromString<LastWaveLyricsResponse>(trimmed)
        } catch (_: Exception) {
            null
        }

        val content = response?.content?.takeIf { it.isNotBlank() } ?: trimmed

        // Check if content is LRC
        val lrcLines = LyricsRepository.parseLrc(content)
        if (lrcLines.isNotEmpty()) {
            return@withContext LyricsResult.Success(
                lines = lrcLines,
                isSynced = true,
                isWordSynced = false,
                plainLyrics = lrcLines.joinToString("\n") { it.text },
                isInstrumental = false,
                source = "LastWave (Line-Sync)",
            )
        }

        // Plain text fallback
        if (content.isNotBlank() && !content.startsWith("{")) {
            return@withContext LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                isWordSynced = false,
                plainLyrics = content.trim(),
                isInstrumental = false,
                source = "LastWave (Plain)",
            )
        }

        null
    }

    private fun extractTtml(raw: String): String? {
        if (!raw.startsWith("{") && !raw.startsWith("[")) return raw
        return try {
            val element = json.parseToJsonElement(raw)
            findTtmlString(element)
        } catch (_: Exception) {
            null
        }
    }

    private fun findTtmlString(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> if (element.isString) {
                element.content.takeIf { "<p" in it.lowercase() || "<tt" in it.lowercase() }
            } else null
            is JsonArray -> element.firstNotNullOfOrNull { findTtmlString(it) }
            is JsonObject -> {
                val keys = listOf("content", "ttml", "ttmlContent", "lyrics", "lrc", "text", "data", "result")
                keys.firstNotNullOfOrNull { element[it]?.let { v -> findTtmlString(v) } }
                    ?: element.values.firstNotNullOfOrNull { findTtmlString(it) }
            }
        }
    }

    companion object {
        private const val USER_AGENT = "LastWave-Android/1.0"
    }
}
