package com.lastwave.app.data.music

/**
 * Metadata and signed direct playback URL for an extracted YouTube video stream.
 */
data class YouTubeVideoStream(
    val videoId: String,
    val url: String,
    val itag: Int? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val durationMs: Long? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val expiresAtEpochMs: Long? = null,
) {
    val displayQuality: String
        get() = when (height) {
            2160 -> "2160p (4K)"
            1440 -> "1440p (2K)"
            1080 -> "1080p (FHD)"
            720 -> "720p (HD)"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            144 -> "144p"
            else -> resolution?.takeIf { it.isNotBlank() }
                ?: if (height != null && height > 0) "${height}p" else "Auto"
        }
}

/**
 * A bundle of all available streams extracted for a YouTube video,
 * allowing instant user quality selection from 144p up to 2160p (4K).
 */
data class SyncedVideoStreamBundle(
    val videoId: String,
    val defaultStream: YouTubeVideoStream,
    val availableStreams: List<YouTubeVideoStream>,
)
