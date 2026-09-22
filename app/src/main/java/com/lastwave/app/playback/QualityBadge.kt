package com.lastwave.app.playback

import kotlin.math.roundToInt

/**
 * Now-playing quality pill text.
 *
 * Lossless is `24/44.1kHz` (bit depth / sample rate). The pill is narrow,
 * so the codec name is not prefixed. Spatial mixes use a short `ATMOS` or
 * `SPATIAL` badge.
 */
fun qualityBadgeLabel(state: MusicPlayerState): String {
    val spatial = spatialIndicatorLabel(state.audioCodec)
    if (spatial != null) return spatial

    val codec = state.audioCodec
    val flacLike = isFlacLikeCodec(codec) || state.isLossless
    val depth = state.bitDepth ?: inferBitDepth(state)
    val rate = state.samplingRateKHz

    if (flacLike && depth != null && rate != null && rate > 0.0) {
        return "$depth/${formatSampleRateKHz(rate)}kHz"
    }
    if (flacLike && rate != null && rate > 0.0) {
        return "${formatSampleRateKHz(rate)}kHz"
    }
    if (flacLike) return codec?.takeIf { it.isNotBlank() && !it.equals("AUDIO", true) } ?: "FLAC"

    if (codec?.equals("MP3 320k", ignoreCase = true) == true) return "MP3 320 kbps"
    if (codec?.uppercase() in GENERIC_AUDIO_LABELS) return "AUDIO"
    if (codec != null && state.bitrateKbps != null) return "${codec.uppercase()} ${state.bitrateKbps} kbps"
    if (codec != null) return codec.uppercase()
    if (state.bitrateKbps != null) return "${state.bitrateKbps} kbps"
    return "AUDIO"
}

/** Compact chip next to the title when the playing stream is spatial. */
fun spatialIndicatorLabel(codec: String?): String? {
    val c = codec?.uppercase().orEmpty()
    if (c.contains("ATMOS")) return "ATMOS"
    if (c.contains("SPATIAL") || c.contains("360")) return "SPATIAL"
    return null
}

fun isSpatialAudioCodec(codec: String?): Boolean = spatialIndicatorLabel(codec) != null

fun isFlacLikeCodec(codec: String?): Boolean {
    val c = codec?.uppercase().orEmpty()
    if (c.isBlank()) return false
    if (isSpatialAudioCodec(codec)) return false
    return c.contains("FLAC") || c == "LOSSLESS" || c.contains("HI-RES") || c.contains("HI_RES")
}

internal fun formatSampleRateKHz(kHz: Double): String {
    val rounded = (kHz * 10.0).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

private fun inferBitDepth(state: MusicPlayerState): Int? {
    val rate = state.samplingRateKHz ?: return null
    val kbps = state.bitrateKbps ?: return null
    if (rate <= 0.0 || kbps <= 0) return null
    val inferred = (kbps * 1000.0 / (rate * 1000.0 * 2.0)).roundToInt()
    return when (inferred) {
        in 15..17 -> 16
        in 23..25 -> 24
        in 31..33 -> 32
        else -> null
    }
}

private val GENERIC_AUDIO_LABELS = setOf("AUDIO", "LOCAL AUDIO")
