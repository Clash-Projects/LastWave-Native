package com.lastwave.app.playback.resolve

import com.lastwave.app.data.music.YouTubeAudioStream
import kotlinx.serialization.Serializable

/**
 * The signed URL written after a successful extract.
 * Playback and prefetch both read this. Metadata around it is not a substitute.
 *
 * [StreamCache] keeps the player fields (mime, headers, itag) required to
 * open ExoPlayer without a second round trip. Those fields are not the key.
 */
@Serializable
data class StreamCacheEntry(
    val trackId: String,
    val youtubeVideoId: String,
    val streamUrl: String,
    val expiresAt: Long,
) {
    fun isExpired(nowMs: Long, marginMs: Long = StreamCache.EXPIRY_MARGIN_MS): Boolean {
        if (streamUrl.isBlank() || expiresAt <= 0L) return true
        return expiresAt - nowMs <= marginMs
    }
}

@Serializable
data class StreamCache(
    val trackId: String,
    val youtubeVideoId: String,
    val streamUrl: String,
    val expiresAt: Long,
    val mimeType: String = "",
    val audioCodec: String? = null,
    val bitrateKbps: Int? = null,
    val durationMs: Long? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val itag: Int? = null,
    val clientProfile: String = "",
    val authScope: String = "",
    val contentLength: Long? = null,
    val isAdaptive: Boolean = false,
    val rawCodec: String? = null,
    val bitrate: Int = 0,
    val sampleRateHz: Int? = null,
    val mediaCacheKey: String = "",
    val userAgent: String? = null,
) {
    val cacheKey: String get() = "$trackId+$youtubeVideoId"

    fun asEntry(): StreamCacheEntry = StreamCacheEntry(
        trackId = trackId,
        youtubeVideoId = youtubeVideoId,
        streamUrl = streamUrl,
        expiresAt = expiresAt,
    )

    /** The URL belongs to the YouTube video. Track id is who stored it. */
    fun matches(identity: TrackIdentity): Boolean =
        youtubeVideoId == identity.youtubeVideoId && streamUrl.isNotBlank()

    fun isExpired(nowMs: Long, marginMs: Long = EXPIRY_MARGIN_MS): Boolean {
        if (expiresAt <= 0L) return true
        return expiresAt - nowMs <= marginMs
    }

    companion object {
        const val EXPIRY_MARGIN_MS = 2 * 60 * 1000L
        /** Used only when the player response has no expire time. Not zero, so a fresh URL is not a miss. */
        const val UNKNOWN_URL_TTL_MS = 5 * 60 * 1000L
    }
}

fun YouTubeAudioStream.toStreamCache(identity: TrackIdentity): StreamCache = StreamCache(
    trackId = identity.trackId,
    youtubeVideoId = identity.youtubeVideoId,
    streamUrl = url,
    expiresAt = expiresAtEpochMs?.takeIf { it > 0L }
        ?: (System.currentTimeMillis() + StreamCache.UNKNOWN_URL_TTL_MS),
    mimeType = mimeType.orEmpty(),
    audioCodec = codec,
    bitrateKbps = bitrate.takeIf { it > 0 }?.let { (it + 500) / 1_000 },
    durationMs = durationMs,
    requestHeaders = requestHeaders,
    itag = itag,
    clientProfile = clientProfile,
    authScope = authScope,
    contentLength = contentLength,
    isAdaptive = isAdaptive,
    rawCodec = codec,
    bitrate = bitrate,
    sampleRateHz = sampleRateHz,
    mediaCacheKey = mediaCacheKey,
    userAgent = userAgent,
)

fun StreamCache.toYouTubeAudioStream(): YouTubeAudioStream = YouTubeAudioStream(
    videoId = youtubeVideoId,
    url = streamUrl,
    itag = itag,
    mimeType = mimeType.ifBlank { null },
    codec = rawCodec ?: audioCodec,
    bitrate = bitrate,
    sampleRateHz = sampleRateHz,
    durationMs = durationMs,
    contentLength = contentLength,
    isAdaptive = isAdaptive,
    clientProfile = clientProfile.ifBlank { "CACHE" },
    authScope = authScope,
    requestHeaders = requestHeaders,
    expiresAtEpochMs = expiresAt.takeIf { it > 0L },
    userAgent = userAgent ?: requestHeaders["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
)
