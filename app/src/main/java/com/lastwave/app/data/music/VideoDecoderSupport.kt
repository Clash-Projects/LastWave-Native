package com.lastwave.app.data.music

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/** Higher is better for the same resolution: H.264 is the one every phone decodes well. */
internal fun videoCodecPreference(codec: String?): Int {
    val value = codec.orEmpty().lowercase()
    return when {
        value.startsWith("avc") -> 3
        value.startsWith("vp9") || value.startsWith("vp09") -> 2
        value.startsWith("hev") || value.startsWith("hvc") -> 1
        else -> 0
    }
}

/** Decoder MIME type for a stream's codec string, or null when it is not recognised. */
internal fun videoCodecMime(codec: String?): String? {
    val value = codec.orEmpty().lowercase()
    return when {
        value.startsWith("avc") -> "video/avc"
        value.startsWith("vp9") || value.startsWith("vp09") -> "video/x-vnd.on2.vp9"
        value.startsWith("av01") -> "video/av01"
        value.startsWith("hev") || value.startsWith("hvc") -> "video/hevc"
        else -> null
    }
}

internal object VideoDecoderSupport {
    private const val TAG = "VideoDecoderSupport"

    private val decoders: List<MediaCodecInfo> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
        }.onFailure { Log.w(TAG, "Could not list decoders", it) }.getOrDefault(emptyList())
    }

    /** Unknown codecs and unreadable capabilities count as decodable; the player has the last word. */
    fun canDecode(codec: String?, width: Int, height: Int): Boolean {
        val mime = videoCodecMime(codec) ?: return true
        if (decoders.isEmpty()) return true
        val w = if (width > 0) width else (height * 16 / 9 + 1) / 2 * 2
        return decoders.any { info ->
            runCatching {
                val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) }
                    ?: return@runCatching false
                val video = info.getCapabilitiesForType(type).videoCapabilities
                    ?: return@runCatching false
                height <= 0 || video.isSizeSupported(w, height) || video.isSizeSupported(height, w)
            }.getOrDefault(false)
        }
    }
}
