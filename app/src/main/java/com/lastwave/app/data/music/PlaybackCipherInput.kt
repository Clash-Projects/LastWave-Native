package com.lastwave.app.data.music

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Direct URLs can still carry an encrypted n challenge; include both input forms. */
internal class PlaybackCipherInput(val source: String, private val field: String) {
    fun toFormatJson(): JsonObject = buildJsonObject { put(field, source) }
}

internal fun playbackCipherInputs(ciphers: List<String>, directUrls: List<String>): List<PlaybackCipherInput> =
    (ciphers.distinct().map { PlaybackCipherInput(it, "signatureCipher") } +
        directUrls.distinct().map { PlaybackCipherInput(it, "url") }).take(8)
