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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class BetterLyricsGetResponse(
    val ttml: String? = null,
)

@Serializable
private data class BetterLyricsTtmlResponse(
    val lyrics: String? = null,
)

/**
 * BetterLyrics word-sync provider (https://lyrics-api.boidu.dev).
 *
 * Free, no API key, GPL-3.0. Serves Apple-Music TTML with per-syllable
 * `<span begin end>` timing, which maps 1:1 onto [LyricLine]/[LyricSyllable].
 *
 * Chain position: before Kugou — both TTML endpoints are
 * tried (`/getLyrics` returns `{"ttml"}`, `/ttml/getLyrics` returns
 * `{"lyrics"}`).
 */
@Singleton
class BetterLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetchWordLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        queryEndpoints(title, artist, durationSeconds, album)?.let { return@withContext it }

        val cleanedTitle = LrclibLyricsApi.cleanTrackTitle(title)
        val cleanedArtist = LrclibLyricsApi.cleanArtistName(artist)
        if (cleanedTitle != title || cleanedArtist != artist) {
            queryEndpoints(cleanedTitle, cleanedArtist, durationSeconds, album)?.let { return@withContext it }
        }

        null
    }

    suspend fun fetchPortatoLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        fetchDocument(
            baseUrl = "https://lyrics-api.boidu.dev/qq/getLyrics",
            title = title,
            artist = artist,
            durationSeconds = durationSeconds,
            album = album,
        ) ?: run {
            val cleanedTitle = LrclibLyricsApi.cleanTrackTitle(title)
            val cleanedArtist = LrclibLyricsApi.cleanArtistName(artist)
            if (cleanedTitle != title || cleanedArtist != artist) {
                fetchDocument(
                    baseUrl = "https://lyrics-api.boidu.dev/qq/getLyrics",
                    title = cleanedTitle,
                    artist = cleanedArtist,
                    durationSeconds = durationSeconds,
                    album = album,
                )
            } else null
        }
    }

    private suspend fun queryEndpoints(
        title: String,
        artist: String,
        durationSeconds: Int?,
        album: String?,
    ): List<LyricLine>? {
        fetchDocument(
            baseUrl = "https://lyrics-api.boidu.dev/getLyrics",
            title = title,
            artist = artist,
            durationSeconds = durationSeconds,
            album = album,
        )?.let { return it }

        fetchDocument(
            baseUrl = "https://lyrics-api.boidu.dev/ttml/getLyrics",
            title = title,
            artist = artist,
            durationSeconds = durationSeconds,
            album = album,
        )?.let { return it }

        fetchDocument(
            baseUrl = "https://lyrics-api.boidu.dev/qq/getLyrics",
            title = title,
            artist = artist,
            durationSeconds = durationSeconds,
            album = album,
        )?.let { return it }

        return null
    }

    private suspend fun fetchDocument(
        baseUrl: String,
        title: String,
        artist: String,
        durationSeconds: Int?,
        album: String?,
    ): List<LyricLine>? {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: return null
        builder.addQueryParameter("s", title.trim())
        builder.addQueryParameter("a", artist.trim())
        if (durationSeconds != null && durationSeconds > 0) {
            builder.addQueryParameter("d", durationSeconds.toString())
        }
        if (!album.isNullOrBlank()) {
            builder.addQueryParameter("al", album.trim())
        }
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
            parseDocument(body)?.takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseDocument(raw: String): List<LyricLine>? {
        val payload = unwrapPayload(raw) ?: return null
        if (payload.isBlank()) return null
        // TTML word timing first via the shared DOM parser.
        if ("<tt" in payload.lowercase() || "http://www.w3.org/ns/ttml" in payload) {
            TtmlParser.parse(payload).takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Karaoke line format with millisecond ranges.
        parseKaraokeLrc(payload).takeIf { it.isNotEmpty() }?.let { return it }
        // Enhanced + plain LRC (word stamps become syllables).
        LyricsRepository.parseEnhancedLrc(payload).takeIf { it.isNotEmpty() }?.let { return it }
        LyricsRepository.parseLrc(payload).takeIf { it.isNotEmpty() }?.let { return it }
        // Legacy regex path for odd TTML shapes the DOM parser skips.
        parseTtml(payload).takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    internal fun unwrapPayload(raw: String): String? {
        val trimmed = raw.replace("\uFEFF", "").trim()
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
        return try {
            val element = json.parseToJsonElement(trimmed)
            extractContent(element)?.trim()?.takeIf { it.isNotEmpty() } ?: trimmed
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun extractContent(element: kotlinx.serialization.json.JsonElement): String? {
        return when (element) {
            is kotlinx.serialization.json.JsonNull -> null
            is kotlinx.serialization.json.JsonPrimitive -> if (element.isString) {
                val text = element.content.trim()
                if (text.isEmpty()) return null
                // Nested JSON string: unwrap one more level.
                if ((text.startsWith("{") || text.startsWith("[")) &&
                    runCatching { json.parseToJsonElement(text) }.getOrNull() != null
                ) {
                    runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { extractContent(it) } ?: text
                } else text
            } else null
            is kotlinx.serialization.json.JsonArray -> element.mapNotNull { extractContent(it) }
                .joinToString("\n").takeIf { it.isNotBlank() }
            is kotlinx.serialization.json.JsonObject -> {
                if (element["isError"]?.toString() == "true" || element["ok"]?.toString() == "false") return null
                val keys = listOf(
                    "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
                    "plainLyrics", "syncedLyrics", "line", "lines", "lyric",
                    "data", "result", "response",
                )
                keys.asSequence().mapNotNull { element[it]?.let { v -> extractContent(v) } }.firstOrNull()
            }
        }
    }

    private val KARAOKE_LINE_REGEX = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val KARAOKE_WORD_REGEX = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
    private val KARAOKE_TIME_REGEX = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")

    internal fun parseKaraokeLrc(raw: String): List<LyricLine> {
        if ("[" !in raw || "(" !in raw) return emptyList()
        val rows = mutableListOf<LyricLine>()
        for (source in raw.lines()) {
            val match = KARAOKE_LINE_REGEX.matchEntire(source.trim()) ?: continue
            val lineStart = match.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = match.groupValues[2].toLongOrNull() ?: 0L
            val body = match.groupValues[3]
            val matches = KARAOKE_WORD_REGEX.findAll(body).toList()
            if (matches.isEmpty()) continue
            val words = mutableListOf<LyricSyllable>()
            val firstMatch = matches.first()
            val leadingText = LyricsRepository.decodeEntities(body.substring(0, firstMatch.range.first)).trim()
            if (leadingText.isNotEmpty()) {
                val firstStart = firstMatch.groupValues[1].toLongOrNull() ?: lineStart
                val startMs = minOf(lineStart, firstStart)
                val durMs = (firstStart - startMs).coerceAtLeast(0L)
                words += LyricSyllable(timeMs = startMs, durationMs = durMs, text = leadingText)
            }
            for (word in matches) {
                val text = LyricsRepository.decodeEntities(word.groupValues[3]).trim()
                if (text.isEmpty()) continue
                val startMs = word.groupValues[1].toLongOrNull() ?: continue
                val durMs = word.groupValues[2].toLongOrNull() ?: 0L
                words += LyricSyllable(timeMs = startMs, durationMs = durMs.coerceAtLeast(0L), text = text)
            }
            if (words.isEmpty()) continue
            val text = LyricsSanitizer.safeLineText(
                LyricsRepository.decodeEntities(body.replace(KARAOKE_TIME_REGEX, "")),
                words.map { it.text },
            )
            if (text.isEmpty()) continue
            rows += LyricLine(
                timeMs = minOf(lineStart, words.first().timeMs),
                durationMs = lineDuration.coerceAtLeast(0L),
                text = text,
                syllables = words,
            )
        }
        return rows.sortedBy { it.timeMs }
    }

    companion object {
        private val P_TAG_REGEX = Regex(
            """<p\b[^>]*>(.*?)</p>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val TIME_ATTR_REGEX = Regex("""\b(begin|end|dur)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val SPAN_TAG_REGEX = Regex(
            """<span\b[^>]*>(.*?)</span>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val XML_TAG_REGEX = Regex("""<[^>]+>""")
        private val WHITESPACE_REGEX = Regex("""\s+""")

        fun parseTtml(ttml: String): List<LyricLine> {
            // Prefer the shared DOM parser; the regex below is the fallback
            // for documents the DOM pass skips.
            TtmlParser.parse(ttml).takeIf { it.isNotEmpty() }?.let { return it }
            val lines = mutableListOf<LyricLine>()
            for (pMatch in P_TAG_REGEX.findAll(ttml)) {
                val openTag = pMatch.value.substringBefore('>')
                val attrs = TIME_ATTR_REGEX.findAll(openTag).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val lineStartMs = parseTtmlTime(attrs["begin"] ?: "") ?: continue
                val lineEndMs = parseTtmlTime(attrs["end"] ?: "")
                    ?: attrs["dur"]?.let { parseTtmlTime(it)?.let { dur -> lineStartMs + dur } }
                    ?: (lineStartMs + 1500L)
                val inner = pMatch.groupValues[1]

                val syllables = mutableListOf<LyricSyllable>()
                val words = mutableListOf<String>()
                val spanMatches = SPAN_TAG_REGEX.findAll(inner).toList()
                if (spanMatches.isNotEmpty()) {
                    val firstSpan = spanMatches.first()
                    val leadingText = unescapeXml(inner.substring(0, firstSpan.range.first).replace(XML_TAG_REGEX, "").trim())
                    if (leadingText.isNotEmpty()) {
                        val firstOpen = firstSpan.value.substringBefore('>')
                        val firstAttrs = TIME_ATTR_REGEX.findAll(firstOpen).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        val firstStart = parseTtmlTime(firstAttrs["begin"] ?: "") ?: lineStartMs
                        val wStart = minOf(lineStartMs, firstStart)
                        val wEnd = maxOf(wStart, firstStart)
                        words.add(leadingText)
                        syllables.add(
                            LyricSyllable(
                                timeMs = wStart,
                                durationMs = (wEnd - wStart).coerceAtLeast(0L),
                                text = leadingText,
                            ),
                        )
                    }
                }
                for (sMatch in spanMatches) {
                    val spanOpen = sMatch.value.substringBefore('>')
                    val spanAttrs = TIME_ATTR_REGEX.findAll(spanOpen).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val wStart = parseTtmlTime(spanAttrs["begin"] ?: "") ?: continue
                    val wEnd = parseTtmlTime(spanAttrs["end"] ?: "")
                        ?: spanAttrs["dur"]?.let { parseTtmlTime(it)?.let { dur -> wStart + dur } }
                        ?: wStart
                    val word = unescapeXml(sMatch.groupValues[1].trim())
                    if (word.isEmpty()) continue
                    words.add(word)
                    syllables.add(
                        LyricSyllable(
                            timeMs = wStart,
                            durationMs = (wEnd - wStart).coerceAtLeast(0L),
                            text = word,
                        ),
                    )
                }

                // Lines without word spans (e.g. instrumental markers) still
                // carry line timing — keep them as line-sync lines.
                val fullLineText = unescapeXml(inner.replace(XML_TAG_REGEX, "")).replace(WHITESPACE_REGEX, " ").trim()
                if (syllables.isEmpty()) {
                    if (fullLineText.isEmpty()) continue
                    lines.add(
                        LyricLine(
                            timeMs = lineStartMs,
                            durationMs = (lineEndMs - lineStartMs).coerceAtLeast(0L),
                            text = fullLineText,
                        ),
                    )
                } else {
                    val text = LyricsSanitizer.safeLineText(fullLineText, words)
                    lines.add(
                        LyricLine(
                            timeMs = lineStartMs,
                            durationMs = (lineEndMs - lineStartMs).coerceAtLeast(0L),
                            text = text,
                            syllables = syllables,
                        ),
                    )
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        /** TTML times are seconds floats ("9.731") or clock times ("3:53.713"). */
        fun parseTtmlTime(raw: String): Long? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            return try {
                if (':' in value) {
                    val parts = value.split(':')
                    var totalMs = 0L
                    for (i in parts.indices) {
                        val part = parts[i].toDoubleOrNull() ?: return null
                        val power = parts.size - 1 - i
                        totalMs += (part * 60.0.pow(power) * 1000L).toLong()
                    }
                    totalMs
                } else {
                    (value.toDoubleOrNull() ?: return null).times(1000L).toLong()
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun Double.pow(n: Int): Double {
            var result = 1.0
            repeat(n) { result *= this }
            return result
        }

        fun unescapeXml(raw: String): String =
            raw.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
    }
}
