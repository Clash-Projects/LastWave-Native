package com.lastwave.app.data.music

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the signed/ciphered YouTube media URLs that raw InnerTube player
 * responses no longer reliably expose. Extraction happens locally; no proxy
 * or account is used.
 */
@Singleton
class YouTubeStreamExtractor @Inject constructor(
    private val http: OkHttpClient,
) {
    private val downloader = OkHttpNewPipeDownloader(http)

    @Volatile
    private var initialized = false

    fun invalidateCache(@Suppress("UNUSED_PARAMETER") videoId: String) = Unit

    suspend fun resolveAudioStream(
        videoId: String,
        preferM4a: Boolean = false,
        preferOpus: Boolean = false,
    ): YouTubeAudioStream = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        initialize()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException("YouTube stream extraction failed for $videoId", error)
        }
        val ordered = info.audioStreams.sortedWith(
            compareBy<org.schabi.newpipe.extractor.stream.AudioStream> { audioPreference(it, preferM4a, preferOpus) }
                .thenByDescending { maxOf(it.averageBitrate, it.bitrate) },
        )
        if (ordered.isEmpty()) throw IOException("YouTube returned no playable audio stream for $videoId")
        val candidates = ordered.map { stream ->
            stream.toYouTubeAudioStream(videoId, info.duration, now)
        }
        val playable = selectProbedCandidate(
            candidates = candidates,
            urlOf = { it.url },
            probe = ::probePlayable,
            limit = MAX_PROBED_AUDIO_STREAMS,
        )
        if (playable != null) return@withContext playable
        invalidatePlayerState(videoId)
        throw IOException("YouTube audio URLs were rejected for $videoId")
    }

    suspend fun resolveVideoStreamBundle(
        videoId: String,
    ): SyncedVideoStreamBundle = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        initialize()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException("YouTube video stream extraction failed for $videoId", error)
        }
        val allVideoStreams = buildList {
            addAll(info.videoStreams)
            addAll(info.videoOnlyStreams)
        }
        val validStreams = allVideoStreams.filter { stream ->
            stream.content.isNotBlank() && (
                stream.format?.mimeType?.contains("mp4", ignoreCase = true) == true ||
                stream.format?.mimeType?.contains("webm", ignoreCase = true) == true
            )
        }
        if (validStreams.isEmpty()) {
            throw IOException("YouTube returned no playable video streams for $videoId")
        }

        // Group by resolution height (supports up to 2160p, 1440p, 1080p, 720p, 480p, 360p, 240p, 144p)
        val streamsByHeight = validStreams
            .map { stream ->
                val h = parseResolutionHeight(stream.resolution, stream.height)
                h to stream
            }
            .filter { it.first > 0 }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, streams) ->
                streams.maxWithOrNull(
                    compareBy<org.schabi.newpipe.extractor.stream.VideoStream> {
                        // Prefer MP4 container for broad hardware decoding compatibility
                        if (it.format?.mimeType?.contains("mp4", ignoreCase = true) == true) 1 else 0
                    }.thenBy { it.bitrate }
                )!!
            }

        val availableStreams = streamsByHeight.keys.sortedDescending().map { height ->
            streamsByHeight[height]!!.toYouTubeVideoStream(videoId, info.duration, now)
        }
        if (availableStreams.isEmpty()) {
            throw IOException("YouTube returned no valid video stream resolutions for $videoId")
        }

        // For default (Auto), prefer 720p or 1080p for instant playback without buffer stalls
        val autoCandidates = availableStreams.sortedWith(
            compareByDescending { stream ->
                val h = stream.height ?: 0
                when (h) {
                    720 -> 100
                    1080 -> 95
                    480 -> 80
                    1440 -> 75
                    2160 -> 70
                    360 -> 60
                    else -> 40
                }
            }
        )
        val playableDefault = selectProbedCandidate(
            candidates = autoCandidates,
            urlOf = { it.url },
            probe = ::probePlayableVideo,
            limit = MAX_PROBED_AUDIO_STREAMS,
        ) ?: availableStreams.first()

        SyncedVideoStreamBundle(
            videoId = videoId,
            defaultStream = playableDefault,
            availableStreams = availableStreams,
        )
    }

    suspend fun resolveVideoStream(
        videoId: String,
    ): YouTubeVideoStream = resolveVideoStreamBundle(videoId).defaultStream

    private fun parseResolutionHeight(resolution: String?, height: Int): Int {
        if (height > 0) return height
        return resolution.orEmpty().filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun probePlayableVideo(stream: YouTubeVideoStream): Boolean {
        val request = playbackProbeRequest(stream.url, stream.requestHeaders)
        val call = http.newCall(request)
        call.timeout().timeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                acceptsPlaybackProbe(response.code, response.header("Content-Type").orEmpty())
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    private fun org.schabi.newpipe.extractor.stream.VideoStream.toYouTubeVideoStream(
        videoId: String,
        durationSeconds: Long,
        now: Long,
    ): YouTubeVideoStream {
        val streamUrl = content
        return YouTubeVideoStream(
            videoId = videoId,
            url = streamUrl,
            itag = itag.takeIf { it >= 0 },
            mimeType = format?.mimeType,
            codec = codec?.takeIf(String::isNotBlank),
            resolution = resolution,
            width = width.takeIf { it > 0 },
            height = height.takeIf { it > 0 },
            bitrate = bitrate.takeIf { it > 0 },
            durationMs = itagItem?.approxDurationMs?.takeIf { it > 0 }
                ?: durationSeconds.takeIf { it > 0 }?.times(1_000L),
            requestHeaders = mapOf(
                "User-Agent" to YOUTUBE_WEB_USER_AGENT,
                "Origin" to YOUTUBE_ORIGIN,
                "Referer" to "$YOUTUBE_ORIGIN/watch?v=$videoId",
            ),
            expiresAtEpochMs = streamUrl.toHttpUrlOrNull()
                ?.queryParameter("expire")
                ?.toLongOrNull()
                ?.times(1_000L)
                ?: (now + UNKNOWN_EXPIRY_TTL_MS),
        )
    }
    private fun org.schabi.newpipe.extractor.stream.AudioStream.toYouTubeAudioStream(
        videoId: String,
        durationSeconds: Long,
        now: Long,
    ): YouTubeAudioStream {
        val reportedBitrate = maxOf(averageBitrate, bitrate)
        return YouTubeAudioStream(
            videoId = videoId,
            url = content,
            itag = itag.takeIf { it >= 0 },
            mimeType = format?.mimeType,
            codec = codec?.takeIf(String::isNotBlank),
            // NewPipe reports kbps while raw InnerTube formats report bps;
            // normalize both providers to bps for one truthful UI value.
            bitrate = if (reportedBitrate in 1..9_999) reportedBitrate * 1_000 else reportedBitrate,
            sampleRateHz = itagItem?.sampleRate?.takeIf { it > 0 },
            durationMs = itagItem?.approxDurationMs?.takeIf { it > 0 }
                ?: durationSeconds.takeIf { it > 0 }?.times(1_000L),
            contentLength = itagItem?.contentLength?.takeIf { it > 0 },
            isAdaptive = true,
            clientProfile = NEWPIPE_CLIENT_PROFILE,
            authScope = ANONYMOUS_AUTH_SCOPE,
            requestHeaders = mapOf(
                "User-Agent" to YOUTUBE_WEB_USER_AGENT,
                "Origin" to YOUTUBE_ORIGIN,
                "Referer" to "$YOUTUBE_ORIGIN/watch?v=$videoId",
            ),
            expiresAtEpochMs = content.toHttpUrlOrNull()
                ?.queryParameter("expire")
                ?.toLongOrNull()
                ?.times(1_000L)
                ?: now + UNKNOWN_EXPIRY_TTL_MS,
            userAgent = YOUTUBE_WEB_USER_AGENT,
        )
    }

    private fun audioPreference(
        stream: org.schabi.newpipe.extractor.stream.AudioStream,
        preferM4a: Boolean,
        preferOpus: Boolean,
    ): Int {
        val mime = stream.format?.mimeType.orEmpty().lowercase()
        val opus = mime.contains("webm") || stream.codec?.contains("opus", ignoreCase = true) == true
        val m4a = mime.contains("mp4") || mime.contains("m4a")
        return when {
            preferOpus && opus -> 0
            preferM4a && m4a -> 0
            stream.itag == 140 || m4a -> 1
            stream.itag == 251 || opus -> 2
            else -> 3
        }
    }

    /**
     * Opens the URL the way ExoPlayer does: no two-byte range. A 206 for
     * `bytes=0-1` is not evidence the player can read the stream. The body
     * is closed as soon as the status line is known.
     */
    private fun probePlayable(stream: YouTubeAudioStream): Boolean {
        val request = playbackProbeRequest(stream.url, stream.requestHeaders)
        val call = http.newCall(request)
        call.timeout().timeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                acceptsPlaybackProbe(response.code, response.header("Content-Type").orEmpty())
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getSignatureTimestamp(videoId: String): Int? = withContext(Dispatchers.IO) {
        initialize()
        try {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    fun decipherStreamUrl(videoId: String, signatureCipher: String): String? {
        initialize()
        val parameters = "https://cipher.invalid/?$signatureCipher".toHttpUrlOrNull() ?: return null
        var resolvedUrl = parameters.queryParameter("url") ?: return null
        val encryptedSignature = parameters.queryParameter("s")
        if (!encryptedSignature.isNullOrBlank()) {
            val signature = runCatching {
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, encryptedSignature)
            }.getOrNull() ?: return null
            if (resolvedUrl.toHttpUrlOrNull() == null) return null
            resolvedUrl = appendQueryParameterPreservingUrl(
                url = resolvedUrl,
                name = parameters.queryParameter("sp") ?: "signature",
                value = signature,
            )
        }
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, resolvedUrl)
        }.getOrDefault(resolvedUrl)
    }

    fun deobfuscateThrottlingParameter(videoId: String, url: String): String {
        initialize()
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        }.getOrDefault(url)
    }

    /** A rejected signed URL usually means NewPipe's cached player script is
     * stale. Clear that state locally so playback can recover without an app
     * force-stop. */
    fun invalidatePlayerState(videoId: String) {
        invalidateCache(videoId)
        runCatching { YoutubeJavaScriptPlayerManager.clearAllCaches() }
    }

    fun preWarm() {
        initialize()
    }

    private fun appendQueryParameterPreservingUrl(url: String, name: String, value: String): String {
        val fragmentIndex = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val base = url.substring(0, fragmentIndex)
        val fragment = url.substring(fragmentIndex)
        val separator = when {
            base.endsWith('?') || base.endsWith('&') -> ""
            '?' in base -> "&"
            else -> "?"
        }
        return "$base$separator${Uri.encode(name)}=${Uri.encode(value)}$fragment"
    }

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(downloader)
            initialized = true
        }
    }

    companion object {
        private const val UNKNOWN_EXPIRY_TTL_MS = 5 * 60 * 1000L
        private const val MAX_PROBED_AUDIO_STREAMS = 4
        private const val PROBE_TIMEOUT_MS = 1_500L
        private const val NEWPIPE_CLIENT_PROFILE = "NEWPIPE"
        private const val ANONYMOUS_AUTH_SCOPE = "anonymous"
    }
}

internal const val YOUTUBE_ORIGIN = "https://www.youtube.com"

/** Same Accept headers as the player's [androidx.media3.datasource.DefaultHttpDataSource]. No two-byte Range. */
internal fun playbackProbeRequest(url: String, headers: Map<String, String>): okhttp3.Request =
    okhttp3.Request.Builder()
        .url(url)
        .header("Accept", "audio/*,*/*;q=0.8")
        .header("Accept-Encoding", "identity")
        .apply {
            headers.forEach { (name, value) -> header(name, value) }
        }
        .build()

/** HTTP 403 and 410 are not playable. A tiny 206 is only accepted when the open itself succeeded. */
internal fun acceptsPlaybackProbe(code: Int, contentType: String): Boolean {
    if (code == 403 || code == 410) return false
    val type = contentType.lowercase()
    val playableType = !type.contains("text/html") &&
        !type.contains("application/json") &&
        !type.contains("text/plain")
    return (code == 200 || code == 206) && playableType
}

/**
 * Probes at most [limit] distinct URLs. A failed candidate is not retried.
 * [probe] may throw [kotlinx.coroutines.CancellationException].
 */
internal fun <T> selectProbedCandidate(
    candidates: List<T>,
    urlOf: (T) -> String,
    probe: (T) -> Boolean,
    limit: Int,
): T? {
    val seen = HashSet<String>()
    var probed = 0
    for (candidate in candidates) {
        if (probed >= limit) break
        val url = urlOf(candidate)
        if (url.isBlank() || !seen.add(url)) continue
        probed++
        if (probe(candidate)) return candidate
    }
    return null
}

private class OkHttpNewPipeDownloader(
    private val http: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val headers = request.headers()
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())

        headers.forEach { (name, values) ->
            values.forEach { value -> requestBuilder.addHeader(name, value) }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", YOUTUBE_WEB_USER_AGENT)
        }
        if (headers.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
            requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
        }
        val origin = if (request.url().contains("music.youtube.com", ignoreCase = true)) {
            "https://music.youtube.com"
        } else {
            YOUTUBE_ORIGIN
        }
        if (headers.keys.none { it.equals("Origin", ignoreCase = true) }) {
            requestBuilder.header("Origin", origin)
        }
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            requestBuilder.header("Referer", "$origin/")
        }

        return http.newCall(requestBuilder.build()).execute().use { response ->
            Response(
                response.code,
                response.message,
                response.headers.names().associateWith(response.headers::values),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }
}

internal const val YOUTUBE_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
