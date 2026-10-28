package com.lastwave.app.data.music

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.*
import org.junit.Test

class PlaybackCipherInputTest {
    @Test fun signatureAndDirectUrlChallengesUseTheirRespectiveFields() {
        val inputs = playbackCipherInputs(listOf("url=encrypted&s=challenge"), listOf("https://media.googlevideo.com/audio?n=encrypted"))
        assertEquals("url=encrypted&s=challenge", inputs[0].toFormatJson()["signatureCipher"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://media.googlevideo.com/audio?n=encrypted", inputs[1].toFormatJson()["url"]?.jsonPrimitive?.contentOrNull)
        assertFalse(inputs[1].toFormatJson().containsKey("signatureCipher"))
    }

    @Test fun directOnlyResponsesStillRunThroughTheDecoder() {
        val inputs = playbackCipherInputs(emptyList(), listOf("https://media.googlevideo.com/audio?n=challenge"))
        assertEquals(1, inputs.size)
        assertTrue(inputs.single().toFormatJson().containsKey("url"))
    }

    @Test fun batchingPreservesOriginalInputsForMappingResults() {
        val direct = "https://media.googlevideo.com/audio?n=a%2Bb&sig=keep%2Fexact"
        assertEquals(direct, playbackCipherInputs(emptyList(), listOf(direct)).single().source)
    }

    @Test fun duplicateInputsAreRemovedAndBatchSizeIsBounded() {
        assertEquals(2, playbackCipherInputs(listOf("cipher", "cipher"), listOf("direct", "direct")).size)
        assertEquals(8, playbackCipherInputs((1..10).map { "cipher-$it" }, listOf("direct")).size)
    }
}
