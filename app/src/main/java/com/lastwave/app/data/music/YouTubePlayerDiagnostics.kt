package com.lastwave.app.data.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Counts only: never include response bodies, cookies, tokens, or signed URLs. */
internal fun youtubePlayerResponseSummary(root: JsonObject): String {
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    val status = (root["playabilityStatus"] as? JsonObject)?.text("status")
        ?.takeIf { it in setOf("OK", "LOGIN_REQUIRED", "UNPLAYABLE", "ERROR", "LIVE_STREAM_OFFLINE") }
        ?: "OTHER"
    val streaming = root["streamingData"] as? JsonObject
    val formats = listOf("formats", "adaptiveFormats").flatMap {
        (streaming?.get(it) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    }
    val audio = formats.filter { it.text("mimeType")?.startsWith("audio/") == true }
    fun JsonObject.hasValue(key: String) = !text(key).isNullOrBlank()
    return "status=$status streaming=${streaming != null} formats=${formats.size} " +
        "audio=${audio.size} directAudio=${audio.count { it.hasValue("url") }} " +
        "cipherAudio=${audio.count { it.hasValue("signatureCipher") || it.hasValue("cipher") }} " +
        "hls=${streaming?.hasValue("hlsManifestUrl") == true} " +
        "dash=${streaming?.hasValue("dashManifestUrl") == true} " +
        "sabr=${streaming?.hasValue("serverAbrStreamingUrl") == true}"
}
