package com.lastwave.app.data.music

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class YouTubePlaybackRequestTest {
    private val context = buildJsonObject {
        put("client", buildJsonObject { put("clientName", "WEB_REMIX") })
    }

    @Test fun dataSaverRequiresAKnownCappedAudioRendition() {
        val audio = YouTubeAudioStream(
            videoId = "video-id", url = "https://example.com/audio", itag = 249,
            mimeType = "audio/webm", codec = "opus", bitrate = 96_000, sampleRateHz = null,
            durationMs = null, contentLength = null, isAdaptive = true, clientProfile = "test", authScope = "",
        )
        assertTrue(audio.meetsBitrateLimit(96_000))
        assertFalse(audio.copy(bitrate = 96_001).meetsBitrateLimit(96_000))
        assertFalse(audio.copy(bitrate = 0).meetsBitrateLimit(96_000))
        assertFalse(audio.copy(url = "https://example.com/live.m3u8?token=test").meetsBitrateLimit(96_000))
        assertTrue(audio.copy(bitrate = 256_000).meetsBitrateLimit(null))
    }

    @Test fun playerTokenIsSentAtRootAlongsideContext() {
        val body = playbackPlayerBody("video-id", context, 20345, "player-token")
        assertEquals("player-token", body["serviceIntegrityDimensions"]!!.jsonObject["poToken"]!!.jsonPrimitive.content)
        assertFalse(body["context"]!!.jsonObject.containsKey("serviceIntegrityDimensions"))
        assertEquals(context, body["context"])
        assertEquals(20345, body["playbackContext"]!!.jsonObject["contentPlaybackContext"]!!.jsonObject["signatureTimestamp"]!!.jsonPrimitive.int)
    }

    @Test fun missingTokenAndTimestampAreOmitted() {
        val body = playbackPlayerBody("video-id", context, null, null)
        assertFalse(body.containsKey("serviceIntegrityDimensions"))
        assertFalse(body.containsKey("playbackContext"))
        assertFalse(playbackPlayerBody("video-id", context, null, "  ").containsKey("serviceIntegrityDimensions"))
    }

    @Test fun signedInClientPrecedesPreviousAnonymousWinner() {
        val ordered = listOf("ANDROID_VR", "IOS", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "WEB_REMIX")
            .sortedBy { playbackClientPriority(it, true, "ANDROID_VR") }
        assertEquals(listOf("WEB_REMIX", "ANDROID_VR", "IOS", "TVHTML5_SIMPLY_EMBEDDED_PLAYER"), ordered)
    }

    @Test fun anonymousPlaybackKeepsPreviousWinnerFirst() {
        val ordered = listOf("ANDROID_VR", "IOS", "WEB_REMIX")
            .sortedBy { playbackClientPriority(it, false, "IOS") }
        assertEquals(listOf("IOS", "ANDROID_VR", "WEB_REMIX"), ordered)
    }
}
