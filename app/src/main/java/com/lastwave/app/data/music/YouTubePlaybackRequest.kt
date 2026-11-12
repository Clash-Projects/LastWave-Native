package com.lastwave.app.data.music

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Give the cookie-capable client a turn before anonymous clients spend the deadline. */
internal fun playbackClientPriority(name: String, connected: Boolean, lastSuccessful: String?): Int = when {
    connected && name == "WEB_REMIX" -> 0
    name == lastSuccessful -> 1
    else -> 2
}

/** Player integrity is a top-level field, separate from the client context. */
internal fun playbackPlayerBody(
    videoId: String,
    context: JsonObject,
    signatureTimestamp: Int?,
    playerPoToken: String?,
): JsonObject = buildJsonObject {
    put("context", context)
    put("videoId", videoId)
    put("contentCheckOk", true)
    put("racyCheckOk", true)
    if (!playerPoToken.isNullOrBlank()) {
        put("serviceIntegrityDimensions", buildJsonObject { put("poToken", playerPoToken) })
    }
    // Only a timestamp extracted from the current player is valid; omit it on extraction failure.
    if (signatureTimestamp != null) {
        put("playbackContext", buildJsonObject {
            put("contentPlaybackContext", buildJsonObject { put("signatureTimestamp", signatureTimestamp) })
        })
    }
}
