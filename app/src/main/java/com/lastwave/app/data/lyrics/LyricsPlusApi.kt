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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LyricsPlusSyllable(
    val time: Long = 0L,
    val duration: Long = 0L,
    val text: String = "",
    val isBackground: Boolean = false,
)

@Serializable
data class LyricsPlusTransliteration(
    val lang: String? = null,
    val text: String? = null,
    val syllabus: List<LyricsPlusSyllable>? = null,
)

@Serializable
data class LyricsPlusLine(
    val time: Long = 0L,
    val duration: Long = 0L,
    val text: String = "",
    val syllabus: List<LyricsPlusSyllable>? = null,
    val transliteration: LyricsPlusTransliteration? = null,
)

@Serializable
data class LyricsPlusResponse(
    val type: String? = null, // "WORD" or "LINE"
    val lyrics: List<LyricsPlusLine>? = null,
    val error: LyricsPlusError? = null,
)

@Serializable
data class LyricsPlusError(
    val message: String? = null,
    val status: Int? = null,
)

@Serializable
private data class TtmlEnvelope(
    val ttml: String? = null,
)

@Singleton
class LyricsPlusApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val strictJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val mirrors = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )
    private val lastGoodHost = AtomicReference<String?>(null)

    suspend fun fetchWordLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        isrc: String? = null,
    ): LyricsPlusResponse? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val cleanedTitle = LrclibLyricsApi.cleanTrackTitle(title)
        val cleanedArtist = LrclibLyricsApi.cleanArtistName(artist)

        // Raw TTML first: word-grade timing beats the syllable endpoint
        // when a mirror holds it; a miss falls through to the v2 rounds.
        raceTtmlMirrors(title, artist, album, durationSeconds)?.let { return@withContext it }

        val directResult = raceMirrors(title, artist, album, durationSeconds, isrc)
        if (directResult != null && !directResult.lyrics.isNullOrEmpty()) {
            return@withContext directResult
        }

        // Duration-gated server miss (music-video vs audio lengths): retry
        // without duration before falling back to other providers.
        if (durationSeconds != null && durationSeconds > 0) {
            val noDurationResult = raceMirrors(title, artist, album, null, isrc)
            if (noDurationResult != null && !noDurationResult.lyrics.isNullOrEmpty()) {
                return@withContext noDurationResult
            }
        }

        if (cleanedTitle != title || cleanedArtist != artist) {
            val cleanedResult = raceMirrors(cleanedTitle, cleanedArtist, album, durationSeconds, isrc)
            if (cleanedResult != null && !cleanedResult.lyrics.isNullOrEmpty()) {
                return@withContext cleanedResult
            }
            if (durationSeconds != null && durationSeconds > 0) {
                val cleanedNoDuration = raceMirrors(cleanedTitle, cleanedArtist, album, null, isrc)
                if (cleanedNoDuration != null && !cleanedNoDuration.lyrics.isNullOrEmpty()) {
                    return@withContext cleanedNoDuration
                }
            }
        }

        null
    }

    /**
     * Raw-TTML round across the same mirrors: Apple-grade word timing when
     * held, converted into the standard response shape so downstream
     * mapping, validation and labels behave identically to v2 hits.
     */
    private suspend fun raceTtmlMirrors(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LyricsPlusResponse? = coroutineScope {
        val hosts = lastGoodHost.get()?.let { good ->
            listOf(good) + mirrors.filterNot { it == good }
        } ?: mirrors
        val pending = hosts.map { host ->
            host to async(Dispatchers.IO) { fetchTtmlFromMirror(host, title, artist, album, durationSeconds) }
        }.toMutableList()
        try {
            while (pending.isNotEmpty()) {
                val (host, result) = select {
                    pending.forEach { (host, job) -> job.onAwait { host to it } }
                }
                pending.removeAll { it.first == host }
                if (result != null) {
                    lastGoodHost.set(host)
                    return@coroutineScope result
                }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetchTtmlFromMirror(
        host: String,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LyricsPlusResponse? {
        val urlBuilder = "$host/v1/ttml/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("title", title.trim())
        urlBuilder.addQueryParameter("artist", artist.trim())
        if (!album.isNullOrBlank()) {
            urlBuilder.addQueryParameter("album", album.trim())
        }
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.addQueryParameter("duration", durationSeconds.toString())
        }
        return try {
            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
                .header("Accept", "application/json")
                .get()
                .build()
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull()?.trim()
                ?: return null
            if (body.isBlank()) return null
            val ttml = if (body.startsWith("<")) {
                body
            } else {
                runCatching {
                    strictJson.decodeFromString<TtmlEnvelope>(body).ttml?.trim()
                        ?: strictJson.parseToJsonElement(body).let { element ->
                            (element as? JsonObject)?.get("ttml")?.jsonPrimitive?.content?.trim()
                        }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            }
            if (!("<tt" in ttml.lowercase() || "http://www.w3.org/ns/ttml" in ttml)) return null
            val lines = TtmlParser.parse(ttml).takeIf { it.isNotEmpty() } ?: return null
            LyricsPlusResponse(
                type = if (lines.any { it.hasSyllables }) "WORD" else "LINE",
                lyrics = lines.map { line ->
                    LyricsPlusLine(
                        time = line.timeMs,
                        duration = line.durationMs,
                        text = line.text,
                        syllabus = line.syllables.map { syl ->
                            LyricsPlusSyllable(
                                time = syl.timeMs,
                                duration = syl.durationMs,
                                text = syl.text,
                                isBackground = syl.isBackground,
                            )
                        }.takeIf { it.isNotEmpty() },
                        transliteration = line.transliteration?.let {
                            LyricsPlusTransliteration(text = it)
                        },
                    )
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun raceMirrors(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        isrc: String?,
    ): LyricsPlusResponse? = coroutineScope {
        val hosts = lastGoodHost.get()?.let { good ->
            listOf(good) + mirrors.filterNot { it == good }
        } ?: mirrors
        val pending = hosts.map { host ->
            host to async(Dispatchers.IO) { fetchFromMirror(host, title, artist, album, durationSeconds, isrc) }
        }.toMutableList()
        try {
            while (pending.isNotEmpty()) {
                val (host, result) = select {
                    pending.forEach { (host, job) -> job.onAwait { host to it } }
                }
                pending.removeAll { it.first == host }
                if (result != null && !result.lyrics.isNullOrEmpty()) {
                    lastGoodHost.set(host)
                    return@coroutineScope result
                }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetchFromMirror(
        host: String,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        isrc: String?,
    ): LyricsPlusResponse? {
        val urlBuilder = "$host/v2/lyrics/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("title", title.trim())
        urlBuilder.addQueryParameter("artist", artist.trim())
        if (!album.isNullOrBlank()) {
            urlBuilder.addQueryParameter("album", album.trim())
        }
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.addQueryParameter("duration", durationSeconds.toString())
        }
        if (!isrc.isNullOrBlank()) {
            urlBuilder.addQueryParameter("isrc", isrc.trim())
        }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
            .header("Accept", "application/json")

        return try {
            val body = okHttpClient.newCall(requestBuilder.build()).awaitSuccessfulBodyOrNull() ?: return null
            val parsed = json.decodeFromString<LyricsPlusResponse>(body)
            parsed.takeIf { !it.lyrics.isNullOrEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
