package com.lastwave.app.data.plugin

import java.util.Locale

/**
 * Builds a minimal single-period DASH MPD from a segmented chunk descriptor.
 *
 * The provider module parses its own manifest format in JS and hands us a
 * [SegmentedStreamDescriptor] (baseUrl + per-chunk mediaRange list).
 * ExoPlayer's DashMediaSource needs MPD XML, so we re-emit exactly one
 * AdaptationSet/Representation with a SegmentList — chunk-by-chunk byte
 * ranges on the same CDN URL.
 *
 * Encrypted: emits Widevine ContentProtection + pssh so ExoPlayer routes
 * samples through MediaCrypto instead of trying clear playback.
 */
object SegmentedMpdBuilder {
    const val WIDEVINE_UUID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    fun build(descriptor: SegmentedStreamDescriptor): String =
        build(descriptor, descriptor.stream.baseUrl)

    /** [baseUrlOverride] points the MPD at a local file/content copy for offline play. */
    fun build(descriptor: SegmentedStreamDescriptor, baseUrlOverride: String? = null): String {
        val s = descriptor.stream
        val baseUrl = baseUrlOverride?.takeIf { it.isNotBlank() } ?: s.baseUrl
        require(baseUrl.isNotBlank()) { "Segmented descriptor missing baseUrl" }
        require(s.segments.isNotEmpty()) { "Segmented descriptor has no segments" }

        val mime = s.mimeType.ifBlank { "audio/mp4" }
        val codecs = when (s.codec.lowercase()) {
            "flac" -> "flac"
            "opus" -> "opus"
            "mp4a", "aac" -> "mp4a.40.2"
            else -> s.codec.ifBlank { "flac" }
        }
        val bandwidth = s.bandwidth.takeIf { it > 0 } ?: 1_000_000
        val sampleRate = s.sampleRate.takeIf { it > 0 } ?: 44100
        val durationSec = descriptor.durationSec.takeIf { it > 0 } ?: 240
        // SegmentList/Duration form: total duration split evenly across chunks.
        val segDur = durationSec.toDouble() / s.segments.size.coerceAtLeast(1)

        val psshBlock = descriptor.drm
            ?.pssh
            ?.takeIf { it.isNotBlank() }
            ?.let { pssh ->
                "<ContentProtection schemeIdUri=\"urn:uuid:$WIDEVINE_UUID\" value=\"Widevine\">" +
                    "<cenc:pssh xmlns:cenc=\"urn:mpeg:cenc:2013\">${pssh.trim()}</cenc:pssh>" +
                    "</ContentProtection>"
            }.orEmpty()

        val segUrls = s.segments.joinToString("") { seg ->
            "<SegmentURL mediaRange=\"${escapeXml(seg.range)}\"/>"
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
            "xmlns:cenc=\"urn:mpeg:cenc:2013\" " +
            "type=\"static\" mediaPresentationDuration=\"PT${durationSec}S\" " +
            "minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\">" +
            "<Period duration=\"PT${durationSec}S\">" +
            "<AdaptationSet mimeType=\"${escapeXml(mime)}\" contentType=\"audio\">" +
            psshBlock +
            "<Representation id=\"${escapeXml(s.quality.ifBlank { "HD" })}\" " +
            "codecs=\"${escapeXml(codecs)}\" bandwidth=\"$bandwidth\" " +
            "audioSamplingRate=\"$sampleRate\">" +
            "<BaseURL>${escapeXml(baseUrl)}</BaseURL>" +
            "<SegmentList duration=\"${"%.3f".format(Locale.US, segDur)}\">" +
            "<Initialization range=\"${escapeXml(s.initializationRange)}\"/>" +
            segUrls +
            "</SegmentList>" +
            "</Representation>" +
            "</AdaptationSet>" +
            "</Period>" +
            "</MPD>"
    }

    private fun escapeXml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
