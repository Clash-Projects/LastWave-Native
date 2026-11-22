package com.lastwave.app.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeProbeSelectionTest {

    @Test
    fun playbackProbeDoesNotUseATwoByteRange() {
        val request = playbackProbeRequest(
            url = "https://example.test/videoplayback?itag=140",
            headers = mapOf(
                "User-Agent" to "LastWave",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/watch?v=abc",
            ),
        )
        assertEquals("bytes=0-1048575", request.header("Range"))
        assertEquals("audio/*,*/*;q=0.8", request.header("Accept"))
        assertEquals("identity", request.header("Accept-Encoding"))
        assertEquals("LastWave", request.header("User-Agent"))
    }

    @Test
    fun playbackProbeReadsPastTheFirstMebibyteWhenTheFileReachesIt() {
        val long = playbackProbeRequest("https://example.test/videoplayback?itag=140&clen=3000000", emptyMap())
        assertEquals("bytes=1048576-2097151", long.header("Range"))

        // Window clipped to the last byte of the file.
        val medium = playbackProbeRequest("https://example.test/videoplayback?itag=140&clen=1500000", emptyMap())
        assertEquals("bytes=1048576-1499999", medium.header("Range"))

        // Too short to have a boundary to test: the opening, clipped to the file.
        val short = playbackProbeRequest("https://example.test/videoplayback?itag=140&clen=500000", emptyMap())
        assertEquals("bytes=0-499999", short.header("Range"))
    }

    @Test
    fun http403IsNotPlayable() {
        assertFalse(acceptsPlaybackProbe(403, "audio/mp4"))
        assertFalse(acceptsPlaybackProbe(410, "audio/mp4"))
        assertFalse(acceptsPlaybackProbe(206, "text/html"))
        assertTrue(acceptsPlaybackProbe(200, "audio/mp4"))
        assertTrue(acceptsPlaybackProbe(206, "audio/webm"))
    }

    @Test
    fun a403CandidateFallsThroughToTheNextUrl() {
        val probed = mutableListOf<String>()
        val selected = selectProbedCandidate(
            candidates = listOf("dead", "live", "unused"),
            urlOf = { it },
            probe = { url ->
                probed += url
                url == "live"
            },
            limit = 4,
        )
        assertEquals("live", selected)
        assertEquals(listOf("dead", "live"), probed)
    }

    @Test
    fun theFifthCandidateIsNeverProbed() {
        val probed = mutableListOf<Int>()
        val selected = selectProbedCandidate(
            candidates = listOf(1, 2, 3, 4, 5),
            urlOf = { "url-$it" },
            probe = { candidate ->
                probed += candidate
                false
            },
            limit = 4,
        )
        assertNull(selected)
        assertEquals(listOf(1, 2, 3, 4), probed)
    }

    @Test
    fun aRepeatedUrlIsNotProbedTwice() {
        val probed = mutableListOf<String>()
        val selected = selectProbedCandidate(
            candidates = listOf("same", "same", "other"),
            urlOf = { it },
            probe = { url ->
                probed += url
                url == "other"
            },
            limit = 4,
        )
        assertEquals("other", selected)
        assertEquals(listOf("same", "other"), probed)
    }
}
