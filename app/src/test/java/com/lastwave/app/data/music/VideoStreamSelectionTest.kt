package com.lastwave.app.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStreamSelectionTest {

    private fun stream(height: Int?, codec: String? = "avc1.4d401f", bitrate: Int = 1_000, mime: String = "video/mp4") =
        YouTubeVideoStream(
            videoId = "id",
            url = "https://example.test/$height/$codec/$bitrate",
            mimeType = mime,
            codec = codec,
            height = height,
            bitrate = bitrate,
        )

    @Test
    fun keepsOneStreamPerHeightHighestFirst() {
        val result = preferredVideoStreamPerHeight(listOf(stream(360), stream(1080), stream(720), stream(720, bitrate = 2_000)))
        assertEquals(listOf(1080, 720, 360), result.map { it.height })
        assertEquals(2_000, result[1].bitrate)
    }

    @Test
    fun prefersAvcOverHigherBitrateAv1AndVp9() {
        val avc = stream(1080, bitrate = 1_000)
        val result = preferredVideoStreamPerHeight(
            listOf(
                stream(1080, codec = "av01.0.08M.08", bitrate = 9_000),
                stream(1080, codec = "vp9", bitrate = 8_000, mime = "video/webm"),
                avc,
            ),
        )
        assertEquals(listOf(avc), result)
    }

    @Test
    fun dropsStreamsWithoutAHeight() {
        assertEquals(emptyList<YouTubeVideoStream>(), preferredVideoStreamPerHeight(listOf(stream(null), stream(0))))
    }

    private fun adaptive(height: Int, codec: String = "avc1.4d401f", mime: String = "video/mp4", label: String = "${height}p") =
        stream(height, codec = codec, bitrate = height * 1_000, mime = mime).copy(
            itag = height,
            width = height * 16 / 9,
            resolution = label,
            durationMs = 61_005,
            url = "https://example.test/v?itag=$height&sig=a",
            initRange = "0-740",
            indexRange = "741-1500",
        )

    @Test
    fun manifestListsRenditionsLowestBitrateFirstWithEscapedUrls() {
        val manifest = buildAdaptiveVideoManifest(listOf(adaptive(720), adaptive(360)))!!
        assertTrue(manifest.indexOf("""height="360"""") < manifest.indexOf("""height="720""""))
        assertTrue(manifest.contains("itag=360&amp;sig=a"))
        assertTrue(manifest.contains("""mediaPresentationDuration="PT61.005S""""))
        assertTrue(manifest.contains("""<SegmentBase indexRange="741-1500"><Initialization range="0-740"/>"""))
    }

    @Test
    fun manifestKeepsOneCodecFamilyAndIncludes4kRenditions() {
        val manifest = buildAdaptiveVideoManifest(
            listOf(
                adaptive(360),
                adaptive(720),
                adaptive(1080, codec = "vp9", mime = "video/webm"),
                adaptive(1608, label = "2160p"),
            ),
        )!!
        assertTrue(manifest.contains("""height="720""""))
        assertFalse(manifest.contains("vp9"))
        assertTrue(manifest.contains("""height="1608""""))
    }

    @Test
    fun noManifestWithoutTwoAdaptiveRenditions() {
        assertNull(buildAdaptiveVideoManifest(listOf(adaptive(720))))
        assertNull(buildAdaptiveVideoManifest(listOf(adaptive(720), adaptive(360).copy(initRange = null))))
        assertNull(buildAdaptiveVideoManifest(listOf(adaptive(720).copy(durationMs = null), adaptive(360).copy(durationMs = null))))
    }

    @Test
    fun adaptiveRenditionWinsOverProgressiveAtTheSameHeight() {
        val progressive = stream(360, bitrate = 9_000)
        val dash = adaptive(360)
        assertEquals(listOf(dash), preferredVideoStreamPerHeight(listOf(progressive, dash)))
    }

    @Test
    fun autoPlaybackUsesQualityLabelForWideFilmHeights() {
        val ranked = rankedForAutoPlayback(
            listOf(
                stream(1608).copy(resolution = "2160p"),
                stream(800).copy(resolution = "1080p60"),
                stream(536).copy(resolution = "720p"),
            ),
        )
        assertEquals(listOf(1608, 800, 536), ranked.map { it.height })
    }

    @Test
    fun autoPlaybackPrefersHighestResolutionThenFallsBack() {
        val ranked = rankedForAutoPlayback(listOf(stream(2160), stream(360), stream(1080), stream(720), stream(480)))
        assertEquals(listOf(2160, 1080, 720, 480, 360), ranked.map { it.height })
    }
}
