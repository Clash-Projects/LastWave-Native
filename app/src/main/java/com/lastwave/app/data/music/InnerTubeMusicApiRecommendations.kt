package com.lastwave.app.data.music

import com.lastwave.app.data.ytmusic.YtConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Feedback tokens are scoped to a song and account; library/undo tokens must never be used. */
internal fun notInterestedToken(root: JsonElement, videoId: String): String? {
    fun menuToken(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull(::menuToken)
        is JsonObject -> {
            val icon = element.obj("icon")?.string("iconType")
                ?: element.obj("defaultIcon")?.string("iconType")
            if (icon == "NOT_INTERESTED") {
                (element.obj("serviceEndpoint") ?: element.obj("defaultServiceEndpoint"))
                    ?.obj("feedbackEndpoint")?.string("feedbackToken")?.takeIf(String::isNotBlank)
            } else element.values.firstNotNullOfOrNull(::menuToken)
        }
        else -> null
    }
    fun find(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull(::find)
        is JsonObject -> {
            val id = element.string("videoId")
                ?: element.obj("playlistItemData")?.string("videoId")
                ?: element.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
                ?: element.obj("overlay")?.obj("musicItemThumbnailOverlayRenderer")
                    ?.obj("content")?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")
                    ?.obj("watchEndpoint")?.string("videoId")
            if (id == videoId && element.obj("navigationEndpoint")?.obj("browseEndpoint") == null) {
                element.obj("menu")?.let(::menuToken)
            } else element.values.firstNotNullOfOrNull(::find)
        }
        else -> null
    }
    return videoId.takeIf(String::isNotBlank)?.let { find(root) }
}

internal fun feedbackWasProcessed(root: JsonObject): Boolean =
    root.array("feedbackResponses")?.let { responses ->
        responses.isNotEmpty() && responses.all { it.string("isProcessed") == "true" }
    } == true

internal suspend fun InnerTubeMusicApi.markNotInterested(
    videoId: String,
    account: YtConnection,
): Boolean = withContext(Dispatchers.IO) {
    if (videoId.isBlank() || !account.isConnected || ytAuth.connection.value != account) return@withContext false
    val config = getWebConfig()
    suspend fun request(endpoint: String, field: String, value: JsonElement): JsonObject = post(
        url = "$MUSIC_API/$endpoint?key=${config.apiKey}&prettyPrint=false",
        body = buildJsonObject {
            put("context", context("WEB_REMIX", config.clientVersion, config.visitorData, authenticated = true))
            put(field, value)
        },
        clientName = "WEB_REMIX",
        clientVersion = config.clientVersion,
        userAgent = WEB_USER_AGENT,
        authenticated = true,
        authenticatedAccount = account,
    )
    val next = request("next", "videoId", JsonPrimitive(videoId))
    // Home recommendations can supply this action even when the player's queue cannot.
    val token = notInterestedToken(next, videoId)
        ?: notInterestedToken(request("browse", "browseId", JsonPrimitive("FEmusic_home")), videoId)
        ?: return@withContext false
    if (ytAuth.connection.value != account) return@withContext false
    feedbackWasProcessed(request("feedback", "feedbackTokens", JsonArray(listOf(JsonPrimitive(token)))))
}
