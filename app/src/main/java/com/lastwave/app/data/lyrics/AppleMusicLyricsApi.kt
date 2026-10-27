package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import com.lastwave.app.data.network.ITunesSearchClient
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
private data class PaxEnvelope(
    val syncType: String? = null,
    val lyrics: List<PaxLine> = emptyList(),
    val lrc: String? = null,
    val plain: String? = null,
)

@Serializable
private data class PaxLine(
    val timestamp: Long = 0L,
    val endtime: Long = 0L,
    val duration: Long = 0L,
    val text: List<PaxWord> = emptyList(),
    val background: Boolean = false,
    val backgroundText: List<PaxWord> = emptyList(),
)

@Serializable
private data class PaxWord(
    val text: String = "",
    val timestamp: Long = 0L,
    val endtime: Long = 0L,
    val duration: Long = 0L,
    val part: Boolean = false,
)

/**
 * Apple Music syllable-synced lyrics via the Lyrically aggregator
 * (https://lyrics.paxsenix.org, free, no API key).
 *
 * Two hops: the iTunes Search API resolves `title + artist` to an Apple
 * Music `trackId`, then `/apple-music/lyrics?v=2` returns the normalised
 * envelope whose `lyrics` array carries per-word `timestamp/endtime`
 * timings (`part: true` = continuation of the previous word, e.g.
 * "with"+"drawals"). The envelope's `lrc`/`plain` fields are the fallback
 * when a track has no syllable timings. Every request identifies as
 * `LastWave` in User-Agent.
 */
@Singleton
class AppleMusicLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val iTunesSearchClient: ITunesSearchClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
    ): LyricsResult.Success? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null

        val trackId = iTunesSearchClient.searchBestMatch(title, artist, durationSeconds)?.trackId
            ?: return@withContext null

        // Direct TTML carries per-syllable timing; try it first and keep
        // the structured envelope as the fallback.
        fetchTtmlLines(trackId)?.takeIf { it.isNotEmpty() }?.let { ttmlLines ->
            val hasWordTiming = ttmlLines.any { it.hasSyllables }
            return@withContext LyricsResult.Success(
                lines = ttmlLines,
                isSynced = true,
                isWordSynced = hasWordTiming,
                plainLyrics = ttmlLines.joinToString("\n") { it.text },
                isInstrumental = false,
                source = if (hasWordTiming) "Apple Music (Word-Sync)" else "Apple Music (Line-Sync)",
            )
        }

        val envelope = fetchEnvelope(trackId) ?: return@withContext null

        val lines = mapLines(envelope.lyrics)
        if (lines.isNotEmpty()) {
            val hasWordTiming = lines.any { it.hasSyllables }
            return@withContext LyricsResult.Success(
                lines = lines,
                isSynced = true,
                isWordSynced = hasWordTiming,
                plainLyrics = lines.joinToString("\n") { it.text },
                isInstrumental = false,
                source = if (hasWordTiming) "Apple Music (Word-Sync)" else "Apple Music (Line-Sync)",
            )
        }

        if (!envelope.lrc.isNullOrBlank()) {
            val lrcLines = LyricsRepository.parseLrc(envelope.lrc)
            if (lrcLines.isNotEmpty()) {
                return@withContext LyricsResult.Success(
                    lines = lrcLines,
                    isSynced = true,
                    isWordSynced = false,
                    plainLyrics = envelope.plain?.takeIf { it.isNotBlank() }
                        ?: lrcLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = "Apple Music (Line-Sync)",
                )
            }
        }

        if (!envelope.plain.isNullOrBlank()) {
            return@withContext LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                isWordSynced = false,
                plainLyrics = envelope.plain.trim(),
                isInstrumental = false,
                source = "Apple Music (Plain)",
            )
        }

        null
    }

    private suspend fun fetchTtmlLines(trackId: Long): List<LyricLine>? {
        val url = "https://lyrics.paxsenix.org/apple-music/lyrics?id=$trackId&ttml=true"
            .toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/xml, */*")
            .get()
            .build()
        return try {
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return null
            // The endpoint answers TTML directly or a JSON envelope holding it.
            val ttml = extractTtml(trimmed) ?: trimmed
            if ("<tt" !in ttml.lowercase() && "http://www.w3.org/ns/ttml" !in ttml) return null
            TtmlParser.parse(ttml).takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
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

    private fun findTtmlString(element: kotlinx.serialization.json.JsonElement): String? {
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> if (element.isString) {
                element.content.takeIf { "<tt" in it.lowercase() }
            } else null
            is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { findTtmlString(it) }
            is kotlinx.serialization.json.JsonObject -> {
                val keys = listOf("ttml", "ttmlContent", "lyrics", "lrc", "content", "text", "data", "result")
                keys.firstNotNullOfOrNull { element[it]?.let { v -> findTtmlString(v) } }
                    ?: element.values.firstNotNullOfOrNull { findTtmlString(it) }
            }
        }
    }

    private suspend fun fetchEnvelope(trackId: Long): PaxEnvelope? {
        val url = "https://lyrics.paxsenix.org/apple-music/lyrics?id=$trackId&v=2"
            .toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
            json.decodeFromString<PaxEnvelope>(body).takeIf {
                it.lyrics.isNotEmpty() || !it.lrc.isNullOrBlank() || !it.plain.isNullOrBlank()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val USER_AGENT = "LastWave"

        /** Syllable envelope -> [LyricLine]. `part` words continue the
         *  previous token ("with"+"drawals"); background vocals ride along
         *  as background syllables without polluting the line text. Lines
         *  carried only by background vocals still produce a line so no
         *  section goes missing. */
        private fun mapLines(lines: List<PaxLine>): List<LyricLine> {
            return lines.mapNotNull { line ->
                val words = line.text.filter { it.text.isNotBlank() }
                // Background-only passage (lead `text` empty): build the
                // line from `backgroundText` so the section isn't dropped.
                val leads = words.isNotEmpty()
                val tokens = mutableListOf<String>()
                val syllables = mutableListOf<LyricSyllable>()
                val sourceWords = if (leads) words else line.backgroundText.filter { it.text.isNotBlank() }
                if (sourceWords.isEmpty()) return@mapNotNull null
                for (word in sourceWords) {
                    val text = word.text.trim()
                    // `part` continues the previous token ("with"+"drawals"):
                    // join without space and flag it so views skip the
                    // visual separator too. Background-only lines are all
                    // continuations of nothing, so they always start tokens.
                    val continues = leads && word.part && tokens.isNotEmpty()
                    if (continues) {
                        tokens[tokens.lastIndex] += text
                    } else {
                        tokens += text
                    }
                    syllables += LyricSyllable(
                        timeMs = word.timestamp.coerceAtLeast(0L),
                        durationMs = (
                            word.duration.takeIf { it > 0 }
                                ?: (word.endtime - word.timestamp)
                            ).coerceAtLeast(0L),
                        text = text,
                        isBackground = line.background || !leads,
                        appendToPrevious = continues,
                    )
                }
                if (leads) {
                    for (word in line.backgroundText.filter { it.text.isNotBlank() }) {
                        syllables += LyricSyllable(
                            timeMs = word.timestamp.coerceAtLeast(0L),
                            durationMs = (
                                word.duration.takeIf { it > 0 }
                                    ?: (word.endtime - word.timestamp)
                                ).coerceAtLeast(0L),
                            text = word.text.trim(),
                            isBackground = true,
                        )
                    }
                }
                val lineDuration = line.duration.takeIf { it > 0 }
                    ?: (line.endtime - line.timestamp).coerceAtLeast(0L)
                LyricLine(
                    timeMs = line.timestamp.coerceAtLeast(0L),
                    durationMs = lineDuration,
                    text = tokens.joinToString(" "),
                    syllables = syllables.sortedBy { it.timeMs },
                )
            }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.let(::clampEdges)
        }

        /**
         * Edge-to-edge timing: a line never stays lit past the next line's
         * start, and a syllable never past the next syllable (or its line
         * end). Generous envelope durations otherwise leave two rows
         * highlighted at once around every boundary.
         */
        private fun clampEdges(lines: List<LyricLine>): List<LyricLine> {
            if (lines.size < 2) return lines
            return lines.mapIndexed { index, line ->
                val nextStart = lines.getOrNull(index + 1)?.timeMs
                var duration = line.durationMs
                if (nextStart != null && nextStart > line.timeMs &&
                    line.timeMs + duration > nextStart
                ) {
                    duration = (nextStart - line.timeMs).coerceAtLeast(100L)
                }
                val lineEnd = line.timeMs + duration
                val syllables = line.syllables
                if (syllables.size < 2) {
                    if (duration != line.durationMs) line.copy(durationMs = duration) else line
                } else {
                    var changed = duration != line.durationMs
                    val clamped = syllables.mapIndexed { si, syl ->
                        val nextSyl = syllables.getOrNull(si + 1)
                        var end = syl.timeMs + syl.durationMs
                        if (nextSyl != null && end > nextSyl.timeMs) {
                            end = nextSyl.timeMs
                            changed = true
                        }
                        if (end > lineEnd) {
                            end = lineEnd
                            changed = true
                        }
                        if (end < syl.timeMs) {
                            end = syl.timeMs
                            changed = true
                        }
                        if (end - syl.timeMs != syl.durationMs) syl.copy(durationMs = end - syl.timeMs)
                        else syl
                    }
                    if (changed) line.copy(durationMs = duration, syllables = clamped) else line
                }
            }
        }
    }
}
