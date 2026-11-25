package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
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

private const val LYRICS_PLUS_UA = "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)"

private val BACKEND_HOSTS = listOf(
    "https://lyricsplus.prjktla.my.id",
    "https://lyricsplus.atomix.one",
    "https://lyricsplus.binimum.org",
    "https://lyricsplus.prjktla.workers.dev",
    "https://lyricsplus-seven.vercel.app",
    "https://lyrics-plus-backend.vercel.app",
)

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

@Singleton
class LyricsPlusLyricsApi @Inject constructor(
    private val http: OkHttpClient,
) {
    private val decoder = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val preferredHost = AtomicReference<String?>(null)

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        album: String? = null,
        isrc: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val orderedHosts = orderedMirrors()
        coroutineScope {
            val activeCalls = orderedHosts
                .associateWith { host ->
                    async(Dispatchers.IO) {
                        queryHost(host, title, artist, durationSeconds, album, isrc)
                    }
                }
                .toMutableMap()

            try {
                while (activeCalls.isNotEmpty()) {
                    val (winnerHost, result) = awaitFirst(activeCalls)
                    activeCalls.remove(winnerHost)
                    if (!result.isNullOrEmpty()) {
                        preferredHost.set(winnerHost)
                        return@coroutineScope result
                    }
                }
                null
            } finally {
                for (job in activeCalls.values) {
                    job.cancel()
                }
            }
        }
    }

    internal fun parse(response: LyricsPlusResponse): List<LyricLine> {
        val rawLines = response.lyrics ?: return emptyList()
        val parsed = ArrayList<LyricLine>(rawLines.size)

        for (entry in rawLines) {
            val startMs = entry.time ?: continue
            val durationMs = entry.duration?.takeIf { it > 0L } ?: 0L
            val words = mergeSyllables(entry.syllabus.orEmpty())

            if (words.isNotEmpty()) {
                parsed.add(
                    LyricLine(
                        timeMs = minOf(startMs, words[0].timeMs),
                        durationMs = durationMs,
                        text = words.joinToString(" ") { word -> word.text },
                        syllables = words,
                    ),
                )
            } else if (!entry.text.isNullOrBlank()) {
                parsed.add(
                    LyricLine(
                        timeMs = startMs,
                        durationMs = durationMs,
                        text = entry.text.trim(),
                    ),
                )
            }
        }
        parsed.sortBy(LyricLine::timeMs)
        return parsed
    }

    internal fun mergeSyllables(syllables: List<LyricsPlusSyllable>): List<LyricSyllable> {
        val merged = mutableListOf<LyricSyllable>()
        val wordBuffer = StringBuilder()
        var wordStartMs = 0L
        var wordEndMs = 0L

        fun commitWord() {
            if (wordBuffer.isEmpty()) return
            val span = (maxOf(wordStartMs, wordEndMs) - wordStartMs).coerceAtLeast(0L)
            merged.add(LyricSyllable(timeMs = wordStartMs, durationMs = span, text = wordBuffer.toString()))
            wordBuffer.setLength(0)
        }

        for (piece in syllables) {
            val rawText = piece.text ?: continue
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) {
                commitWord()
                continue
            }

            val timestamp = piece.time ?: continue
            val attachToCurrent = wordBuffer.isNotEmpty() &&
                !rawText.first().isWhitespace() &&
                !wordBuffer.last().isCjkOrThai() &&
                !trimmed.first().isCjkOrThai()

            if (!attachToCurrent) {
                commitWord()
            }
            if (wordBuffer.isEmpty()) {
                wordStartMs = timestamp
                wordEndMs = timestamp
            }

            wordBuffer.append(trimmed)
            wordEndMs = maxOf(wordEndMs, timestamp + (piece.duration ?: 0L))
            if (rawText.last().isWhitespace()) {
                commitWord()
            }
        }
        commitWord()
        return merged
    }

    private fun orderedMirrors(): List<String> {
        val recent = preferredHost.get() ?: return BACKEND_HOSTS
        return buildList(BACKEND_HOSTS.size) {
            add(recent)
            for (mirror in BACKEND_HOSTS) {
                if (mirror != recent) add(mirror)
            }
        }
    }

    private suspend fun awaitFirst(
        pending: Map<String, Deferred<List<LyricLine>?>>,
    ): Pair<String, List<LyricLine>?> = select {
        for ((host, job) in pending) {
            job.onAwait { lines -> host to lines }
        }
    }

    private suspend fun queryHost(
        host: String,
        title: String,
        artist: String,
        durationSeconds: Int?,
        album: String?,
        isrc: String?,
    ): List<LyricLine>? {
        val endpoint = "$host/v2/lyrics/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        endpoint.addQueryParameter("title", title.trim())
        endpoint.addQueryParameter("artist", artist.trim())
        if (durationSeconds != null && durationSeconds > 0) {
            endpoint.addQueryParameter("duration", durationSeconds.toString())
        }
        if (!album.isNullOrBlank()) endpoint.addQueryParameter("album", album.trim())
        if (!isrc.isNullOrBlank()) endpoint.addQueryParameter("isrc", isrc.trim())

        val req = Request.Builder()
            .url(endpoint.build())
            .header("User-Agent", LYRICS_PLUS_UA)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            val payload = http.newCall(req).awaitSuccessfulBodyOrNull() ?: return null
            val decoded = decoder.decodeFromString<LyricsPlusResponse>(payload)
            parse(decoded).takeIf { it.isNotEmpty() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

private fun Char.isCjkOrThai(): Boolean = when (this) {
    in '\u3400'..'\u9FFF',
    in '\u3040'..'\u30FF',
    in '\u3000'..'\u303F',
    in '\uFF00'..'\uFFEF',
    in '\u0E00'..'\u0E7F',
    -> true
    else -> false
}
