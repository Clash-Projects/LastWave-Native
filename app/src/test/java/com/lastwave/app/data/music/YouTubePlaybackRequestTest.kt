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
