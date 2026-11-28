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
import okhttp3.HttpUrl
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

private const val MXM_ENDPOINT = "https://apic.musixmatch.com/ws/1.1"
private const val MXM_APP_ID = "web-desktop-app-v1.0"
private const val MXM_HMAC_KEY = "RJDefUswhwjkZDeM"
private const val MXM_USER_AGENT = "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)"

@Serializable
private data class ApiResponse<T>(val message: MessageBox<T>)

@Serializable
private data class MessageBox<T>(
    val header: StatusHeader,
    val body: T? = null,
)

@Serializable
private data class StatusHeader(
    @SerialName("status_code") val statusCode: Int = 0,
)

@Serializable
private data class TokenPayload(
    @SerialName("user_token") val userToken: String? = null,
)

@Serializable
private data class TrackListPayload(
    @SerialName("track_list") val trackList: List<TrackEntry> = emptyList(),
)

@Serializable
private data class TrackEntry(val track: TrackInfo)

@Serializable
private data class TrackInfo(
    @SerialName("track_id") val trackId: Long = 0L,
    @SerialName("track_name") val trackName: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("track_length") val trackLength: Int? = null,
    @SerialName("has_subtitles") val hasSubtitles: Int = 0,
)

@Serializable
private data class SubtitlePayload(val subtitle: SubtitleData? = null)

@Serializable
private data class SubtitleData(
    @SerialName("subtitle_body") val subtitleBody: String? = null,
)

@Serializable
private data class TimedLine(
    val text: String = "",
    val time: LineTimestamp = LineTimestamp(),
)

@Serializable
private data class LineTimestamp(val total: Double = 0.0)

@Singleton
class MusixmatchLyricsApi @Inject constructor(
    private val http: OkHttpClient,
) {
    private val codec = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val authLock = Mutex()
    private val sessionToken = AtomicReference<String?>(null)

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        try {
            val match = findBestMatch(title, artist, durationSeconds ?: 0) ?: return@withContext null
            if (match.hasSubtitles != 1) return@withContext null

            val rawSubtitle = downloadSubtitle(match.trackId) ?: return@withContext null
            val formattedLrc = convertToLrc(rawSubtitle)
            if (formattedLrc.isEmpty()) return@withContext null

            LyricsRepository.parseLrc(formattedLrc).takeIf { it.isNotEmpty() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findBestMatch(title: String, artist: String, durationSec: Int): TrackInfo? {
        val candidates = queryTracks(title, artist)
        if (candidates.isNullOrEmpty()) return null

        val wantedArtist = artist.trim().lowercase(Locale.ROOT)
        var best: TrackInfo? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (candidate in candidates) {
            val s = evaluateTrack(candidate, title, artist, durationSec)
            if (s > bestScore) {
                bestScore = s
                best = candidate
            }
        }

        val winner = best ?: return null
        val artistMatches = artist.isBlank() ||
            winner.artistName.trim().lowercase(Locale.ROOT).contains(wantedArtist)
        return if (artistMatches && bestScore >= 80.0) winner else null
    }

    private fun evaluateTrack(track: TrackInfo, title: String, artist: String, durationSec: Int): Double {
        val trackTitle = track.trackName.trim().lowercase(Locale.ROOT)
        val wantedTitle = title.trim().lowercase(Locale.ROOT)

        var total = when {
            trackTitle == wantedTitle -> 80.0
            trackTitle.contains(wantedTitle) || wantedTitle.contains(trackTitle) -> 40.0
            else -> 0.0
        }

        val trackArtist = track.artistName.trim().lowercase(Locale.ROOT)
        val wantedArtist = artist.trim().lowercase(Locale.ROOT)
        if (trackArtist.contains(wantedArtist)) {
            total += 40.0
        }

        val trackLen = track.trackLength
        if (trackLen != null && durationSec > 0) {
            val delta = abs(trackLen - durationSec)
            total += when {
                delta <= 2 -> 30.0
                delta <= 5 -> 15.0
                delta <= 10 -> 5.0
                else -> -20.0
            }
        }
        return total
    }

    private suspend fun queryTracks(title: String, artist: String): List<TrackInfo>? {
        val raw = authorizedGet { token ->
            "$MXM_ENDPOINT/track.search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("app_id", MXM_APP_ID)
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

        return try {
            codec.decodeFromString<ApiResponse<TrackListPayload>>(raw)
                .message.body?.trackList?.map(TrackEntry::track)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun downloadSubtitle(trackId: Long): String? {
        val raw = authorizedGet { token ->
            "$MXM_ENDPOINT/track.subtitle.get".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("app_id", MXM_APP_ID)
                .addQueryParameter("track_id", trackId.toString())
                .addQueryParameter("subtitle_format", "mxm")
                .addQueryParameter("usertoken", token)
                .build()
        } ?: return null

        return try {
            codec.decodeFromString<ApiResponse<SubtitlePayload>>(raw)
                .message.body?.subtitle?.subtitleBody
        } catch (_: Exception) {
            null
        }
    }

    private fun convertToLrc(subtitleJson: String): String {
        val entries = try {
            codec.decodeFromString<List<TimedLine>>(subtitleJson)
        } catch (_: Exception) {
            return ""
        }

        val out = StringBuilder()
        for (entry in entries) {
            if (entry.text.isBlank()) continue
            val totalMillis = (entry.time.total * 1000).toLong()
            val mins = totalMillis / 60_000
            val secs = (totalMillis / 1_000) % 60
            val ms = totalMillis % 1_000
            out.append("[%02d:%02d.%03d]%s\n".format(Locale.US, mins, secs, ms, entry.text))
        }
        return out.toString().trim()
    }

    private suspend fun authorizedGet(urlFactory: (String) -> HttpUrl): String? {
        val activeToken = resolveToken() ?: return null
        val response = executeGet(appendSignature(urlFactory(activeToken).toString()))
        if (response != null && !isAuthError(response)) return response

        sessionToken.set(null)
        val renewedToken = resolveToken() ?: return null
        return executeGet(appendSignature(urlFactory(renewedToken).toString()))
    }

    private fun isAuthError(payload: String): Boolean {
        val status = try {
            codec.decodeFromString<ApiResponse<JsonElement>>(payload).message.header.statusCode
        } catch (_: Exception) {
            return false
        }
        return status == 401 || status == 402
    }

    private suspend fun resolveToken(): String? {
        sessionToken.get()?.let { return it }
        return authLock.withLock {
            sessionToken.get() ?: requestNewToken()?.also(sessionToken::set)
        }
    }

    private suspend fun requestNewToken(): String? {
        val baseUrl = "$MXM_ENDPOINT/token.get".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("app_id", MXM_APP_ID)
            .build()
        val payload = executeGet(appendSignature(baseUrl.toString())) ?: return null
        return try {
            codec.decodeFromString<ApiResponse<TokenPayload>>(payload)
                .message.body?.userToken?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun appendSignature(unsignedUrl: String): String {
        val utcDate = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val hmac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(MXM_HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val digest = hmac.doFinal("$unsignedUrl$utcDate".toByteArray(Charsets.UTF_8))
        val encoded = URLEncoder.encode(Base64.getEncoder().encodeToString(digest), "UTF-8")
        return "$unsignedUrl&signature=$encoded&signature_protocol=sha256"
    }

    private suspend fun executeGet(targetUrl: String): String? {
        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", MXM_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            http.newCall(req).awaitSuccessfulBodyOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
