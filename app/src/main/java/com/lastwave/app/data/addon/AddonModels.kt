package com.lastwave.app.data.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Wire models for the Addon HTTP protocol.
 *
 * Endpoints:
 *  - GET {baseUrl}/manifest.json
 *  - GET {baseUrl}/search?q=...&quality=...&atmos=...
 *  - GET {baseUrl}/stream/{id}?quality=...&atmos=...
 */

@Serializable
data class AddonManifest(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("resources") val resources: List<String> = emptyList(),
    @SerialName("root") val root: String = "",
) {
    fun declares(resource: String): Boolean =
        resources.any { it.equals(resource, ignoreCase = true) }

    val isPlayable: Boolean
        get() = resources.isEmpty() || declares("search") || declares("stream")

    val displayName: String
        get() = name.ifBlank { id }
}

@Serializable
data class AddonSearchResponse(
    @SerialName("tracks") val tracks: List<AddonTrack> = emptyList(),
)



private val ATMOS_HINT = Regex("""atmos|dolby|eac3[_-]?joc|ec-?3""", RegexOption.IGNORE_CASE)

@Serializable
data class AddonTrack(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("duration") val duration: Double = 0.0,
    @SerialName("format") val format: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("quality") val quality: String = "",
    @SerialName("audio_quality") val audioQualitySnake: String = "",
    @SerialName("atmos") val atmos: Boolean = false,
    @SerialName("audioMode") val audioMode: String? = null,
    @SerialName("audioModes") val audioModes: List<String> = emptyList(),
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("bitDepth") val rawBitDepth: JsonElement? = null,
    @SerialName("bit_depth") val rawBitDepthSnake: JsonElement? = null,
    @SerialName("bit_type") val rawBitTypeSnake: JsonElement? = null,
    @SerialName("bitType") val rawBitType: JsonElement? = null,
    @SerialName("bit_depth_rate") val rawBitDepthRateSnake: JsonElement? = null,
    @SerialName("bitDepthRate") val rawBitDepthRate: JsonElement? = null,
    @SerialName("bitsPerSample") val rawBitsPerSample: JsonElement? = null,
    @SerialName("bits_per_sample") val rawBitsPerSampleSnake: JsonElement? = null,
    @SerialName("depth") val rawDepth: JsonElement? = null,
    @SerialName("bits") val rawBits: JsonElement? = null,
    @SerialName("clockRate") val rawClockRate: JsonElement? = null,
    @SerialName("clock_rate") val rawClockRateSnake: JsonElement? = null,
    @SerialName("sampleRate") val rawSampleRate: JsonElement? = null,
    @SerialName("sample_rate") val rawSampleRateSnake: JsonElement? = null,
    @SerialName("samplingRate") val rawSamplingRate: JsonElement? = null,
    @SerialName("sampling_rate") val rawSamplingRateSnake: JsonElement? = null,
    @SerialName("metadata") val rawMetadata: JsonElement? = null,
    @SerialName("meta") val rawMeta: JsonElement? = null,
    @SerialName("mediaMetadata") val rawMediaMetadata: JsonElement? = null,
    @SerialName("media_metadata") val rawMediaMetadataSnake: JsonElement? = null,
    @SerialName("audio") val rawAudio: JsonElement? = null,
    @SerialName("streamInfo") val rawStreamInfo: JsonElement? = null,
    @SerialName("stream_info") val rawStreamInfoSnake: JsonElement? = null,
    @SerialName("info") val rawInfo: JsonElement? = null,
    @SerialName("streamURL") val streamURL: String? = null,
    @SerialName("streamUrl") val streamUrlCamel: String? = null,
) {
    val directStreamUrl: String?
        get() = streamURL?.takeIf { it.isNotBlank() } ?: streamUrlCamel?.takeIf { it.isNotBlank() }

    val bitDepth: Int?
        get() = extractBitDepthFromElement(rawMetadata)
            ?: extractBitDepthFromElement(rawMeta)
            ?: extractBitDepthFromElement(rawMediaMetadata)
            ?: extractBitDepthFromElement(rawMediaMetadataSnake)
            ?: extractBitDepthFromElement(rawAudio)
            ?: extractBitDepthFromElement(rawBitDepth)
            ?: extractBitDepthFromElement(rawBitDepthSnake)
            ?: extractBitDepthFromElement(rawBitType)
            ?: extractBitDepthFromElement(rawBitTypeSnake)
            ?: extractBitDepthFromElement(rawBitDepthRate)
            ?: extractBitDepthFromElement(rawBitDepthRateSnake)
            ?: extractBitDepthFromElement(rawBitsPerSample)
            ?: extractBitDepthFromElement(rawBitsPerSampleSnake)
            ?: extractBitDepthFromElement(rawDepth)
            ?: extractBitDepthFromElement(rawBits)
            ?: extractBitDepthFromElement(rawStreamInfo)
            ?: extractBitDepthFromElement(rawStreamInfoSnake)
            ?: extractBitDepthFromElement(rawInfo)
            ?: parseDepthFromQualityString(audioQuality)
            ?: parseDepthFromQualityString(audioQualitySnake)
            ?: parseDepthFromQualityString(quality)
            ?: parseDepthFromQualityString(format)

    val sampleRate: Double?
        get() = extractSampleRateFromElement(rawMetadata)
            ?: extractSampleRateFromElement(rawMeta)
            ?: extractSampleRateFromElement(rawMediaMetadata)
            ?: extractSampleRateFromElement(rawMediaMetadataSnake)
            ?: extractSampleRateFromElement(rawAudio)
            ?: extractSampleRateFromElement(rawClockRate)
            ?: extractSampleRateFromElement(rawClockRateSnake)
            ?: extractSampleRateFromElement(rawSampleRate)
            ?: extractSampleRateFromElement(rawSampleRateSnake)
            ?: extractSampleRateFromElement(rawSamplingRate)
            ?: extractSampleRateFromElement(rawSamplingRateSnake)
            ?: extractSampleRateFromElement(rawBitDepthRate)
            ?: extractSampleRateFromElement(rawBitDepthRateSnake)
            ?: extractSampleRateFromElement(rawStreamInfo)
            ?: extractSampleRateFromElement(rawStreamInfoSnake)
            ?: extractSampleRateFromElement(rawInfo)
            ?: parseRateFromQualityString(audioQuality)
            ?: parseRateFromQualityString(audioQualitySnake)
            ?: parseRateFromQualityString(quality)
            ?: parseRateFromQualityString(format)

    val isDolbyAtmos: Boolean
        get() = atmos ||
            ATMOS_HINT.containsMatchIn(
                "$audioQuality $quality $audioQualitySnake ${audioMode.orEmpty()} ${audioModes.joinToString(" ")} $format"
            )
}

@Serializable
data class AddonStream(
    @SerialName("url") val url: String = "",
    @SerialName("dataUrl") val dataUrl: String? = null,
    @SerialName("format") val format: String = "dash",
    @SerialName("codec") val codec: String = "flac",
    @SerialName("quality") val quality: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("audio_quality") val audioQualitySnake: String = "",
    @SerialName("sampleRate") val rawSampleRate: JsonElement? = null,
    @SerialName("sample_rate") val rawSampleRateSnake: JsonElement? = null,
    @SerialName("clockRate") val rawClockRate: JsonElement? = null,
    @SerialName("clock_rate") val rawClockRateSnake: JsonElement? = null,
    @SerialName("samplingRate") val rawSamplingRate: JsonElement? = null,
    @SerialName("sampling_rate") val rawSamplingRateSnake: JsonElement? = null,
    @SerialName("bitDepth") val rawBitDepth: JsonElement? = null,
    @SerialName("bit_depth") val rawBitDepthSnake: JsonElement? = null,
    @SerialName("bit_type") val rawBitTypeSnake: JsonElement? = null,
    @SerialName("bitType") val rawBitType: JsonElement? = null,
    @SerialName("bit_depth_rate") val rawBitDepthRateSnake: JsonElement? = null,
    @SerialName("bitDepthRate") val rawBitDepthRate: JsonElement? = null,
    @SerialName("bitsPerSample") val rawBitsPerSample: JsonElement? = null,
    @SerialName("bits_per_sample") val rawBitsPerSampleSnake: JsonElement? = null,
    @SerialName("depth") val rawDepth: JsonElement? = null,
    @SerialName("bits") val rawBits: JsonElement? = null,
    @SerialName("bitrate") val bitrate: Int? = null,
    @SerialName("manifest") val manifest: String = "dash",
    @SerialName("manifestXml") val manifestXml: String? = null,
    @SerialName("audioMode") val audioMode: String? = null,
    @SerialName("audioModes") val audioModes: List<String> = emptyList(),
    @SerialName("atmos") val atmos: Boolean = false,
    @SerialName("streamQuality") val streamQuality: String = "",
    @SerialName("encrypted") val encrypted: Boolean = false,
    @SerialName("metadata") val rawMetadata: JsonElement? = null,
    @SerialName("meta") val rawMeta: JsonElement? = null,
    @SerialName("mediaMetadata") val rawMediaMetadata: JsonElement? = null,
    @SerialName("media_metadata") val rawMediaMetadataSnake: JsonElement? = null,
    @SerialName("audio") val rawAudio: JsonElement? = null,
    @SerialName("streamInfo") val rawStreamInfo: JsonElement? = null,
    @SerialName("stream_info") val rawStreamInfoSnake: JsonElement? = null,
    @SerialName("streamMetadata") val rawStreamMetadata: JsonElement? = null,
    @SerialName("stream_metadata") val rawStreamMetadataSnake: JsonElement? = null,
    @SerialName("info") val rawInfo: JsonElement? = null,
) {
    val bitDepth: Int?
        get() = extractBitDepthFromElement(rawMetadata)
            ?: extractBitDepthFromElement(rawMeta)
            ?: extractBitDepthFromElement(rawMediaMetadata)
            ?: extractBitDepthFromElement(rawMediaMetadataSnake)
            ?: extractBitDepthFromElement(rawAudio)
            ?: extractBitDepthFromElement(rawBitDepth)
            ?: extractBitDepthFromElement(rawBitDepthSnake)
            ?: extractBitDepthFromElement(rawBitType)
            ?: extractBitDepthFromElement(rawBitTypeSnake)
            ?: extractBitDepthFromElement(rawBitDepthRate)
            ?: extractBitDepthFromElement(rawBitDepthRateSnake)
            ?: extractBitDepthFromElement(rawBitsPerSample)
            ?: extractBitDepthFromElement(rawBitsPerSampleSnake)
            ?: extractBitDepthFromElement(rawDepth)
            ?: extractBitDepthFromElement(rawBits)
            ?: extractBitDepthFromElement(rawStreamInfo)
            ?: extractBitDepthFromElement(rawStreamInfoSnake)
            ?: extractBitDepthFromElement(rawStreamMetadata)
            ?: extractBitDepthFromElement(rawStreamMetadataSnake)
            ?: extractBitDepthFromElement(rawInfo)
            ?: parseDepthFromQualityString(quality)
            ?: parseDepthFromQualityString(audioQuality)
            ?: parseDepthFromQualityString(audioQualitySnake)
            ?: parseDepthFromQualityString(format)

    val sampleRate: Double
        get() = extractSampleRateFromElement(rawMetadata)
            ?: extractSampleRateFromElement(rawMeta)
            ?: extractSampleRateFromElement(rawMediaMetadata)
            ?: extractSampleRateFromElement(rawMediaMetadataSnake)
            ?: extractSampleRateFromElement(rawAudio)
            ?: extractSampleRateFromElement(rawClockRate)
            ?: extractSampleRateFromElement(rawClockRateSnake)
            ?: extractSampleRateFromElement(rawSampleRate)
            ?: extractSampleRateFromElement(rawSampleRateSnake)
            ?: extractSampleRateFromElement(rawSamplingRate)
            ?: extractSampleRateFromElement(rawSamplingRateSnake)
            ?: extractSampleRateFromElement(rawBitDepthRate)
            ?: extractSampleRateFromElement(rawBitDepthRateSnake)
            ?: extractSampleRateFromElement(rawStreamInfo)
            ?: extractSampleRateFromElement(rawStreamInfoSnake)
            ?: extractSampleRateFromElement(rawStreamMetadata)
            ?: extractSampleRateFromElement(rawStreamMetadataSnake)
            ?: extractSampleRateFromElement(rawInfo)
            ?: parseRateFromQualityString(quality)
            ?: parseRateFromQualityString(audioQuality)
            ?: parseRateFromQualityString(audioQualitySnake)
            ?: parseRateFromQualityString(format)
            ?: 44100.0

    val isDolbyAtmos: Boolean
        get() = atmos ||
            ATMOS_HINT.containsMatchIn(
                "$quality $streamQuality $audioQuality $audioQualitySnake ${audioMode.orEmpty()} ${audioModes.joinToString(" ")} $format $codec"
            )
}

fun extractBitDepthFromElement(element: JsonElement?): Int? {
    if (element == null || element is JsonNull) return null
    if (element is JsonPrimitive) {
        element.intOrNull?.let { if (it in 8..32) return it }
        val str = element.content
        parseDepthFromQualityString(str)?.let { return it }
        Regex("""\b(16|24|32)\b""").find(str)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }
    if (element is JsonObject) {
        val depthKeys = listOf(
            "bitDepth", "bit_depth", "bitdepth",
            "bitType", "bit_type", "bittype",
            "bitsPerSample", "bits_per_sample", "bitspersample",
            "depth", "bits", "bit_depth_rate", "bitDepthRate", "bitdepthrate",
        )
        for (key in depthKeys) {
            val v = element[key]
            val parsed = extractBitDepthFromElement(v)
            if (parsed != null && parsed in 8..32) return parsed
        }
        val textKeys = listOf(
            "format", "audioQuality", "audio_quality", "quality", "codec",
            "audioMode", "audio_mode", "streamInfo", "stream_info", "info", "description",
        )
        for (key in textKeys) {
            val v = element[key]
            if (v is JsonPrimitive) {
                val parsed = parseDepthFromQualityString(v.content)
                if (parsed != null) return parsed
            }
        }
        val nestedKeys = listOf(
            "metadata", "meta", "mediaMetadata", "media_metadata",
            "audio", "stream", "track", "data", "info",
            "streamInfo", "stream_info", "streamMetadata", "stream_metadata",
            "audioInfo", "audio_info",
        )
        for (key in nestedKeys) {
            val child = element[key]
            if (child != null && child !is JsonPrimitive) {
                val parsed = extractBitDepthFromElement(child)
                if (parsed != null) return parsed
            }
        }
        for ((k, v) in element) {
            val lk = k.lowercase()
            if (lk.contains("depth") || lk.contains("bit") || lk == "type") {
                val parsed = extractBitDepthFromElement(v)
                if (parsed != null) return parsed
            }
        }
    }
    if (element is JsonArray) {
        for (item in element) {
            val parsed = extractBitDepthFromElement(item)
            if (parsed != null) return parsed
        }
    }
    return null
}

fun extractSampleRateFromElement(element: JsonElement?): Double? {
    if (element == null || element is JsonNull) return null
    if (element is JsonPrimitive) {
        element.doubleOrNull?.let { raw ->
            if (raw > 0.0) {
                return if (raw < 1000.0) raw * 1000.0 else raw
            }
        }
        val str = element.content
        parseRateFromQualityString(str)?.let { return it }
        val hzMatch = Regex("""\b(\d+(?:\.\d+)?)\s*(?:k|khz)?\b""", RegexOption.IGNORE_CASE).find(str)
        if (hzMatch != null) {
            val num = hzMatch.groupValues[1].toDoubleOrNull() ?: return null
            return if ((str.contains("k", ignoreCase = true) || num < 1000.0) && num < 1000.0) num * 1000.0 else num
        }
        return null
    }
    if (element is JsonObject) {
        val rateKeys = listOf(
            "clockRate", "clock_rate", "clockrate",
            "sampleRate", "sample_rate", "samplerate",
            "samplingRate", "sampling_rate", "samplingrate",
            "rate", "clock", "bit_depth_rate", "bitDepthRate", "bitdepthrate",
            "audioSamplingRate", "audio_sampling_rate",
        )
        for (key in rateKeys) {
            val v = element[key]
            val parsed = extractSampleRateFromElement(v)
            if (parsed != null && parsed > 0.0) return parsed
        }
        val textKeys = listOf(
            "format", "audioQuality", "audio_quality", "quality", "codec",
            "streamInfo", "stream_info", "info", "description",
        )
        for (key in textKeys) {
            val v = element[key]
            if (v is JsonPrimitive) {
                val parsed = parseRateFromQualityString(v.content)
                if (parsed != null && parsed > 0.0) return parsed
            }
        }
        val nestedKeys = listOf(
            "metadata", "meta", "mediaMetadata", "media_metadata",
            "audio", "stream", "track", "data", "info",
            "streamInfo", "stream_info", "streamMetadata", "stream_metadata",
            "audioInfo", "audio_info",
        )
        for (key in nestedKeys) {
            val child = element[key]
            if (child != null && child !is JsonPrimitive) {
                val parsed = extractSampleRateFromElement(child)
                if (parsed != null && parsed > 0.0) return parsed
            }
        }
        for ((k, v) in element) {
            val lk = k.lowercase()
            if (lk.contains("rate") || lk.contains("clock") || lk.contains("sample")) {
                val parsed = extractSampleRateFromElement(v)
                if (parsed != null && parsed > 0.0) return parsed
            }
        }
    }
    if (element is JsonArray) {
        for (item in element) {
            val parsed = extractSampleRateFromElement(item)
            if (parsed != null && parsed > 0.0) return parsed
        }
    }
    return null
}



internal fun parseDepthFromQualityString(q: String?): Int? {
    if (q.isNullOrBlank()) return null
    val match = Regex("""(?:^|[^\d])(16|24|32)\s*(?:[-_]bit)?\s*[/]\s*(\d{2,6}(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(q)
    if (match != null) return match.groupValues[1].toIntOrNull()
    if (q.contains("24-BIT", ignoreCase = true) || q.contains("24BIT", ignoreCase = true) || q.contains("24 BIT", ignoreCase = true) || q.contains("24_BIT", ignoreCase = true) || q.contains("24/")) return 24
    if (q.contains("32-BIT", ignoreCase = true) || q.contains("32BIT", ignoreCase = true) || q.contains("32 BIT", ignoreCase = true) || q.contains("32_BIT", ignoreCase = true) || q.contains("32/")) return 32
    if (q.contains("16-BIT", ignoreCase = true) || q.contains("16BIT", ignoreCase = true) || q.contains("16 BIT", ignoreCase = true) || q.contains("16_BIT", ignoreCase = true) || q.contains("16/")) return 16
    if (q.contains("HI_RES", ignoreCase = true) || q.contains("HI-RES", ignoreCase = true) || q.contains("HIRES", ignoreCase = true) || q.contains("ULTRA_HD", ignoreCase = true)) return 24
    if (q.contains("LOSSLESS", ignoreCase = true) || q.contains("CD", ignoreCase = true) || q.contains("HD", ignoreCase = true) || q.equals("FLAC", ignoreCase = true)) return 16
    val exactNum = q.trim().toIntOrNull()
    if (exactNum in 8..32) return exactNum
    return null
}

internal fun parseRateFromQualityString(q: String?): Double? {
    if (q.isNullOrBlank()) return null
    val match = Regex("""(?:^|[^\d])(?:16|24|32)\s*(?:[-_]bit)?\s*[/]\s*(\d{2,6}(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(q)
    if (match != null) {
        val num = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (num < 1000.0) num * 1000.0 else num
    }
    val rateMatch = Regex("""\b(44\.1|48|88\.2|96|176\.4|192|384)\s*k(?:hz)?\b""", RegexOption.IGNORE_CASE).find(q)
    if (rateMatch != null) {
        val kHz = rateMatch.groupValues[1].toDoubleOrNull() ?: return null
        return kHz * 1000.0
    }
    val hzMatch = Regex("""\b(44100|48000|88200|96000|176400|192000|352800|384000)\b""").find(q)
    if (hzMatch != null) {
        return hzMatch.groupValues[1].toDoubleOrNull()
    }
    if (q.contains("LOSSLESS", ignoreCase = true) || q.contains("CD", ignoreCase = true) || q.equals("FLAC", ignoreCase = true)) {
        return 44100.0
    }
    return null
}

sealed interface AddonHealth {
    data class Ok(val info: String?) : AddonHealth
    data class Unreachable(val reason: String) : AddonHealth
    data class Rejected(val reason: String) : AddonHealth
}
