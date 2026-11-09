package com.lastwave.app.data.music

/**
 * Keeps one stream per resolution, highest resolution first. AVC wins within a
 * resolution because it has the broadest hardware decoder support, then bitrate.
 */
internal fun preferredVideoStreamPerHeight(streams: List<YouTubeVideoStream>): List<YouTubeVideoStream> =
    streams
        .filter { (it.height ?: 0) > 0 }
        .groupBy { it.height!! }
        .toSortedMap(compareByDescending { it })
        .values
        .map { sameHeight ->
            sameHeight.maxWith(
                compareBy<YouTubeVideoStream> { videoCodecRank(it) }
                    .thenBy { it.hasDashRanges }
                    .thenBy { it.bitrate ?: 0 },
            )
        }

/**
 * Builds an on-demand DASH manifest so the player can switch quality with
 * bandwidth. Switching only works within one codec, so this keeps the family
 * with the most renditions, AVC on a tie. Returns null when fewer than two
 * qualities remain, because there is then nothing to adapt between.
 */
internal fun buildAdaptiveVideoManifest(streams: List<YouTubeVideoStream>): String? {
    val usable = streams.filter {
        it.hasDashRanges && (it.width ?: 0) > 0 && (it.height ?: 0) > 0 && (it.bitrate ?: 0) > 0 &&
            !it.codec.isNullOrBlank() && !it.mimeType.isNullOrBlank() && nominalHeight(it) <= MAX_ADAPTIVE_HEIGHT
    }
    val family = usable
        .groupBy { videoCodecRank(it) to it.mimeType }
        .values
        .maxWithOrNull(compareBy<List<YouTubeVideoStream>> { it.size }.thenBy { videoCodecRank(it.first()) })
        ?.takeIf { it.size >= 2 }
        ?: return null
    val durationMs = family.firstNotNullOfOrNull { it.durationMs?.takeIf { ms -> ms > 0 } } ?: return null
    val seconds = "${durationMs / 1_000}.${(durationMs % 1_000).toString().padStart(3, '0')}"
    val representations = family.sortedBy { it.bitrate }.joinToString("") { stream ->
        """<Representation id="${stream.itag ?: stream.height}" codecs="${xmlEscape(stream.codec!!)}" """ +
            """width="${stream.width}" height="${stream.height}" bandwidth="${stream.bitrate}">""" +
            "<BaseURL>${xmlEscape(stream.url)}</BaseURL>" +
            """<SegmentBase indexRange="${stream.indexRange}"><Initialization range="${stream.initRange}"/></SegmentBase>""" +
            "</Representation>"
    }
    return """<?xml version="1.0" encoding="UTF-8"?>""" +
        """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" minBufferTime="PT1.5S" """ +
        """mediaPresentationDuration="PT${seconds}S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">""" +
        """<Period><AdaptationSet contentType="video" mimeType="${xmlEscape(family.first().mimeType!!)}" subsegmentAlignment="true">""" +
        representations +
        "</AdaptationSet></Period></MPD>"
}

private const val MAX_ADAPTIVE_HEIGHT = 2160

private val YouTubeVideoStream.hasDashRanges: Boolean
    get() = !initRange.isNullOrBlank() && !indexRange.isNullOrBlank()

private fun nominalHeight(stream: YouTubeVideoStream): Int =
    stream.resolution?.substringBefore('p')?.toIntOrNull() ?: stream.height ?: 0

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** Try the highest available resolution first; lower renditions remain fallbacks. */
internal fun rankedForAutoPlayback(streams: List<YouTubeVideoStream>): List<YouTubeVideoStream> =
    streams.sortedByDescending(::nominalHeight)

private fun videoCodecRank(stream: YouTubeVideoStream): Int {
    val codec = stream.codec.orEmpty().lowercase()
    return when {
        codec.startsWith("avc") -> 3
        codec.startsWith("vp9") || codec.startsWith("vp09") -> 2
        stream.mimeType?.contains("mp4", ignoreCase = true) == true -> 1
        else -> 0
    }
}
