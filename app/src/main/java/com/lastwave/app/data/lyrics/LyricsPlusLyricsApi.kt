package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class LyricsPlusResponse(
    val type: String? = null,
    val lyrics: List<LyricsPlusLine>? = null,
)

@Serializable
internal data class LyricsPlusLine(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    @SerialName("syllabus") val syllabus: List<LyricsPlusSyllable>? = null,
)

@Serializable
internal data class LyricsPlusSyllable(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
)

/**
 * Syllable-timed lyrics from LyricsPlus, the open backend behind the YouLy+
 * extension (Apple/QQ/Musixmatch aggregation).
 *
 * Hosting is the catch: it runs on volunteer mirrors and at any moment most
 * are rate-limited or gone, so all mirrors are asked at once and the first
 * real answer wins. The winner is remembered so the next track goes straight
 * to a host that was up a minute ago.
 */
@Singleton
class LyricsPlusLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val lastGood = AtomicReference<String?>(null)

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        album: String? = null,
        isrc: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        val hosts = lastGood.get()
            ?.let { remembered -> listOf(remembered) + MIRRORS.filterNot { it == remembered } }
            ?: MIRRORS
        coroutineScope {
            val pending = hosts.map { host ->
                host to async(Dispatchers.IO) { fetch(host, title, artist, durationSeconds, album, isrc) }
            }.toMutableList()
            try {
                // First mirror to answer with something usable, not the first
                // to answer at all: a mirror that 404s this track must not
                // beat one that has it.
                while (pending.isNotEmpty()) {
                    val (host, lines) = select {
                        pending.forEach { (candidate, job) -> job.onAwait { candidate to it } }
                    }
                    pending.removeAll { it.first == host }
                    if (!lines.isNullOrEmpty()) {
                        lastGood.set(host)
                        return@coroutineScope lines
                    }
                }
                null
            } finally {
                pending.forEach { it.second.cancel() }
            }
        }
    }

    private suspend fun fetch(
        host: String,
        title: String,
        artist: String,
        durationSeconds: Int?,
        album: String?,
        isrc: String?,
    ): List<LyricLine>? {
        val builder = "$host/v2/lyrics/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        builder.addQueryParameter("title", title.trim())
        builder.addQueryParameter("artist", artist.trim())
        val seconds = durationSeconds?.takeIf { it > 0 }
        if (seconds != null) builder.addQueryParameter("duration", seconds.toString())
        if (!album.isNullOrBlank()) builder.addQueryParameter("album", album.trim())
        // Sent alongside the name rather than instead of it: the backend
        // aggregates several catalogues and not all of them index an ISRC.
        if (!isrc.isNullOrBlank()) builder.addQueryParameter("isrc", isrc.trim())
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
            val response = runCatching { json.decodeFromString<LyricsPlusResponse>(body) }.getOrNull()
                ?: return null
            parse(response).takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    internal fun parse(response: LyricsPlusResponse): List<LyricLine> {
        return response.lyrics.orEmpty().mapNotNull { line ->
            val start = line.time ?: return@mapNotNull null
            val words = mergeSyllables(line.syllabus.orEmpty())
            when {
                words.isNotEmpty() -> LyricLine(
                    timeMs = minOf(start, words.first().timeMs),
                    durationMs = line.duration?.takeIf { it > 0 } ?: 0L,
                    text = words.joinToString(" ") { it.text },
                    syllables = words,
                )
                // Some sources are only line-synced; still worth showing.
                !line.text.isNullOrBlank() -> LyricLine(
                    timeMs = start,
                    durationMs = line.duration?.takeIf { it > 0 } ?: 0L,
                    text = line.text.trim(),
                )
                else -> null
            }
        }.sortedBy { it.timeMs }
    }

    /**
     * Glues syllables back into words: the API's own spacing is the word
     * boundary — it emits "e" then "nough ", and the trailing space is the
     * only thing saying those are one word. Each merged word keeps its own
     * span so the karaoke sweep still follows the timing.
     */
    internal fun mergeSyllables(syllables: List<LyricsPlusSyllable>): List<LyricSyllable> {
        val words = mutableListOf<LyricSyllable>()
        val current = StringBuilder()
        var start = 0L
        var end = 0L
        fun flush() {
            if (current.isNotEmpty()) {
                words += LyricSyllable(
                    timeMs = start,
                    durationMs = (maxOf(start, end) - start).coerceAtLeast(0L),
                    text = current.toString(),
                )
            }
            current.setLength(0)
        }
        for (syllable in syllables) {
            val raw = syllable.text ?: continue
            val content = raw.trim()
            if (content.isEmpty()) {
                flush()
                continue
            }
            val time = syllable.time ?: continue
            // A run that neither ends in whitespace nor is followed by one is
            // the front of a word that carries on. Scripts written without
            // spaces (Han, kana, Thai) are not stating word boundaries, so
            // each run there stays a word of its own.
            val joins = current.isNotEmpty() && !raw.first().isWhitespace() &&
                !current.last().isUnspacedScript() && !content.first().isUnspacedScript()
            if (!joins) flush()
            if (current.isEmpty()) {
                start = time
                end = time
            }
            current.append(content)
            end = maxOf(end, time + (syllable.duration ?: 0L))
            if (raw.last().isWhitespace()) flush()
        }
        flush()
        return words
    }

    companion object {
        private const val USER_AGENT = "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)"

        private val MIRRORS = listOf(
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app",
            "https://lyrics-plus-backend.vercel.app",
        )
    }
}

/** Scripts written without spaces between words. */
private fun Char.isUnspacedScript(): Boolean =
    this in '\u3400'..'\u9FFF' || // Han
        this in '\u3040'..'\u30FF' || // hiragana and katakana
        this in '\u3000'..'\u303F' || // CJK punctuation
        this in '\uFF00'..'\uFFEF' || // full-width forms
        this in '\u0E00'..'\u0E7F' // Thai
