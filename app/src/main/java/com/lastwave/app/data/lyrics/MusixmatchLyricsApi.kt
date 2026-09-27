package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Serializable
private data class MxmEnvelope<T>(val message: MxmMessage<T>)

@Serializable
private data class MxmMessage<T>(val header: MxmHeader, val body: T? = null)

@Serializable
private data class MxmHeader(@SerialName("status_code") val statusCode: Int = 0)

@Serializable
private data class MxmTokenBody(@SerialName("user_token") val userToken: String? = null)

@Serializable
private data class MxmTrackSearchBody(@SerialName("track_list") val trackList: List<MxmTrackWrapper> = emptyList())

@Serializable
private data class MxmTrackWrapper(val track: MxmTrack)

@Serializable
private data class MxmTrack(
    @SerialName("track_id") val trackId: Long = 0L,
    @SerialName("track_name") val trackName: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("track_length") val trackLength: Int? = null,
    @SerialName("has_subtitles") val hasSubtitles: Int = 0,
)

@Serializable
private data class MxmSubtitleBody(val subtitle: MxmSubtitle? = null)

@Serializable
private data class MxmSubtitle(@SerialName("subtitle_body") val subtitleBody: String? = null)

@Serializable
private data class MxmSubtitleLine(val text: String = "", val time: MxmSubtitleTime = MxmSubtitleTime())

@Serializable
private data class MxmSubtitleTime(val total: Double = 0.0)

/**
 * Line-synced lyrics from the largest lyrics catalogue, via its web client.
 * No API key is needed; requests carry the client's own signature scheme
 * plus a session token that is refreshed when the service rejects it.
 */
@Singleton
class MusixmatchLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val tokenMutex = Mutex()
    private val cachedToken = AtomicReference<String?>(null)

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        try {
            val track = bestTrack(title, artist, durationSeconds ?: 0) ?: return@withContext null
            if (track.hasSubtitles != 1) return@withContext null
            val subtitle = fetchSubtitle(track.trackId) ?: return@withContext null
            val lrc = subtitleToLrc(subtitle).takeIf { it.isNotBlank() } ?: return@withContext null
            LyricsRepository.parseLrc(lrc).takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun bestTrack(title: String, artist: String, seconds: Int): MxmTrack? {
        val tracks = searchTrack(title, artist) ?: return null
        if (tracks.isEmpty()) return null
        // Both sides must agree: title-exact homonyms and same-singer wrong
        // songs otherwise win on a high partial score with wrong timing.
        // 80 = exact title + artist, or contains-title + artist — and the
        // artist side is mandatory whenever the request names one.
        return tracks.maxByOrNull { score(it, title, artist, seconds) }
            ?.takeIf { track ->
                val artistOk = artist.isBlank() || track.artistName.trim().lowercase(Locale.ROOT)
                    .contains(artist.trim().lowercase(Locale.ROOT))
                artistOk && score(track, title, artist, seconds) >= 80.0
            }
    }

    private fun score(track: MxmTrack, title: String, artist: String, seconds: Int): Double {
        var score = 0.0
        val name = track.trackName.trim().lowercase(Locale.ROOT)
        val targetTitle = title.trim().lowercase(Locale.ROOT)
        score += when {
            name == targetTitle -> 80.0
            name.contains(targetTitle) || targetTitle.contains(name) -> 40.0
            else -> 0.0
        }
        if (track.artistName.trim().lowercase(Locale.ROOT).contains(artist.trim().lowercase(Locale.ROOT))) {
            score += 40.0
        }
        track.trackLength?.let { length ->
            val diff = abs(length - seconds)
            score += when {
                seconds <= 0 -> 0.0
                diff <= 2 -> 30.0
                diff <= 5 -> 15.0
                diff <= 10 -> 5.0
                else -> -20.0
            }
        }
        return score
    }

    private suspend fun searchTrack(title: String, artist: String): List<MxmTrack>? {
        val response = signedGet { token ->
            "$BASE/track.search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("app_id", "web-desktop-app-v1.0")
                .addQueryParameter("q_track", title)
                .addQueryParameter("q_artist", artist)
                .addQueryParameter("f_has_lyrics", "1")
                .addQueryParameter("s_track_rating", "desc")
                .addQueryParameter("quorum_factor", "1")
                .addQueryParameter("page_size", "10")
                .addQueryParameter("page", "1")
                .addQueryParameter("usertoken", token)
                .build()
        } ?: return null
        return runCatching {
            json.decodeFromString<MxmEnvelope<MxmTrackSearchBody>>(response)
        }.getOrNull()?.message?.body?.trackList?.map { it.track }
    }

    private suspend fun fetchSubtitle(trackId: Long): String? {
        val response = signedGet { token ->
            "$BASE/track.subtitle.get".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("app_id", "web-desktop-app-v1.0")
                .addQueryParameter("track_id", trackId.toString())
                .addQueryParameter("subtitle_format", "mxm")
                .addQueryParameter("usertoken", token)
                .build()
        } ?: return null
        return runCatching {
            json.decodeFromString<MxmEnvelope<MxmSubtitleBody>>(response)
        }.getOrNull()?.message?.body?.subtitle?.subtitleBody
    }

    private fun subtitleToLrc(subtitleBody: String): String {
        val lines = runCatching { json.decodeFromString<List<MxmSubtitleLine>>(subtitleBody) }
            .getOrNull() ?: return ""
        return buildString {
            for (line in lines) {
                if (line.text.isBlank()) continue
                val totalMs = (line.time.total * 1000).toLong()
                val minutes = totalMs / 1000 / 60
                val seconds = (totalMs / 1000) % 60
                val millis = totalMs % 1000
                appendLine(
                    "[" + "%02d:%02d.%03d".format(Locale.US, minutes, seconds, millis) + "]" + line.text,
                )
            }
        }.trim()
    }

    private suspend fun signedGet(buildUrl: (token: String) -> okhttp3.HttpUrl): String? {
        val token = getToken() ?: return null
        val first = plainGet(sign(buildUrl(token).toString()))
        if (first != null && !looksUnauthorized(first)) return first
        cachedToken.set(null)
        val fresh = getToken() ?: return null
        return plainGet(sign(buildUrl(fresh).toString()))
    }

    private fun looksUnauthorized(body: String): Boolean =
        runCatching { json.decodeFromString<MxmEnvelope<JsonElement>>(body) }
            .getOrNull()?.message?.header?.statusCode?.let { it == 401 || it == 402 } ?: false

    private suspend fun getToken(): String? = cachedToken.get() ?: tokenMutex.withLock {
        cachedToken.get() ?: fetchToken()?.also { cachedToken.set(it) }
    }

    private suspend fun fetchToken(): String? {
        val url = "$BASE/token.get".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("app_id", "web-desktop-app-v1.0")
            .build()
        val body = plainGet(sign(url.toString())) ?: return null
        return runCatching {
            json.decodeFromString<MxmEnvelope<MxmTokenBody>>(body)
        }.getOrNull()?.message?.body?.userToken?.takeIf { it.isNotBlank() }
    }

    private fun sign(url: String): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGNING_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal("$url$date".toByteArray(Charsets.UTF_8))
        val signature = Base64.getEncoder().encodeToString(raw)
        return "$url&signature=${URLEncoder.encode(signature, "UTF-8")}&signature_protocol=sha256"
    }

    private suspend fun plainGet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
                .header("Accept", "application/json")
                .get()
                .build()
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val BASE = "https://apic.musixmatch.com/ws/1.1"
        private const val SIGNING_SECRET = "RJDefUswhwjkZDeM"
    }
}
