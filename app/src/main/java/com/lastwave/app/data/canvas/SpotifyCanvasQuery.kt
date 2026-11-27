package com.lastwave.app.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object SpotifyCanvasQuery {

    const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"
    const val OPERATION = "canvas"
    const val KNOWN_CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val mp4Pattern = Regex("""\.mp4(?:[?#]|$)""", RegexOption.IGNORE_CASE)

    sealed interface Answer {
        data class Found(val url: String, val type: String?) : Answer
        data class NoCanvas(val detail: String) : Answer
        data class Failed(val reason: String, val staleHash: Boolean = false) : Answer
    }

    fun requestBody(trackUri: String, hash: String): String = buildJsonObject {
        put("operationName", OPERATION)
        putJsonObject("variables") {
            put("trackUri", trackUri)
        }
        putJsonObject("extensions") {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", hash)
            }
        }
    }.toString()

    fun parse(body: String): Answer {
        val payload = try {
            parser.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Answer.Failed("not JSON")

        val errorMessages = (payload["errors"] as? JsonArray)
            ?.mapNotNull { entry -> (entry as? JsonObject)?.readString("message") }
            .orEmpty()

        val dataNode = payload["data"] as? JsonObject
        if (dataNode == null) {
            val summary = if (errorMessages.isEmpty()) "no data" else errorMessages.joinToString("; ")
            val hashRetired = errorMessages.any { msg ->
                msg.contains("PersistedQueryNotFound", ignoreCase = true)
            }
            return Answer.Failed(reason = summary, staleHash = hashRetired)
        }

        val canvasNode = (dataNode["trackUnion"] as? JsonObject)?.get("canvas") as? JsonObject
            ?: return Answer.NoCanvas("canvas null")

        val canvasType = canvasNode.readString("type")
        val fileId = canvasNode.readString("fileId")
        val rawUrl = canvasNode.readString("url")
        val summary = "type=$canvasType fileId=$fileId url=$rawUrl"

        if (rawUrl == null || !rawUrl.startsWith("https://") || !mp4Pattern.containsMatchIn(rawUrl)) {
            return Answer.NoCanvas(summary)
        }
        return Answer.Found(rawUrl, canvasType)
    }

    private fun JsonObject.readString(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}
