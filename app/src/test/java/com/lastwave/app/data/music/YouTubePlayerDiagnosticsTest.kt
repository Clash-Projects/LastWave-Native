package com.lastwave.app.data.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class YouTubePlayerDiagnosticsTest {
    @Test fun distinguishesCipheredAudioFromSabrOnlyResponses() {
        val cipher = Json.parseToJsonElement("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","signatureCipher":"secret-cipher"}]}}""").jsonObject
        val result = youtubePlayerResponseSummary(cipher)
        assertTrue(result.contains("cipherAudio=1"))
        assertTrue(result.contains("directAudio=0"))
        assertFalse(result.contains("secret-cipher"))
        val sabr = Json.parseToJsonElement("""{"playabilityStatus":{"status":"OK"},"streamingData":{"serverAbrStreamingUrl":"https://secret.example/token"}}""").jsonObject
        val summary = youtubePlayerResponseSummary(sabr)
        assertTrue(summary.contains("audio=0"))
        assertTrue(summary.contains("sabr=true"))
        assertFalse(summary.contains("secret.example"))
    }

    @Test fun distinguishesBlockedResponsesAndManifestTransports() {
        val root = Json.parseToJsonElement("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"secret-message"}}""").jsonObject
        val summary = youtubePlayerResponseSummary(root)
        assertTrue(summary.contains("status=LOGIN_REQUIRED streaming=false"))
        assertFalse(summary.contains("secret-message"))
        val hls = Json.parseToJsonElement("""{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"secret-manifest"}}""").jsonObject
        assertTrue(youtubePlayerResponseSummary(hls).contains("hls=true"))
    }
}
