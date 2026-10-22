package com.lastwave.app.data.canvas

import com.lastwave.app.data.spotify.SpotifyWebPlayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * How a track's canvas is asked for on the current web player: the `canvas` operation on
 * Pathfinder v2, rather than the protobuf endpoint on spclient.
 *
 * The protobuf route is kept as a fallback, but it is the older of the two and it does not
 * answer for every track. The operation here is the one the web player itself calls.
 *
 * Like every Pathfinder operation, this one is named by a persisted-query hash that changes
 * whenever the web player is rebuilt. [KNOWN_CANVAS_HASH] is the last one known to work;
 * the live one is read out of the web player's own script by [SpotifyWebPlayer], so a
 * rebuild does not quietly take the whole source down.
 *
 * This file holds the request and the answer and nothing else: no HTTP client, so the
 * parsing can be tested without a network.
 */
object SpotifyCanvasQuery {

    const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"
    const val OPERATION = "canvas"

    /** The `canvas` operation's hash as of the last time it was read off the web player. */
    const val KNOWN_CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The body of the `POST` to [ENDPOINT]. */
    fun requestBody(trackUri: String, hash: String): String = buildJsonObject {
        put("operationName", OPERATION)
        putJsonObject("variables") { put("trackUri", trackUri) }
        putJsonObject("extensions") {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", hash)
            }
        }
    }.toString()

    /** What an answer to the query said. */
    sealed interface Answer {
        /** The track has a canvas at [url]. */
        data class Found(val url: String, val type: String?) : Answer

        /**
         * The track has no canvas this can play. [detail] says what came back instead, for the
         * log: no canvas at all, or one in a shape that is not a plain video file.
         */
        data class NoCanvas(val detail: String) : Answer

        /**
         * The query itself did not work: unparseable, or GraphQL errors and no data. A hash
         * that has been retired reads as a "persisted query not found" error, which [staleHash]
         * reports so the hash can be looked up again before giving up.
         */
        data class Failed(val reason: String, val staleHash: Boolean = false) : Answer
    }

    /** Reads the canvas out of an answer. */
    fun parse(body: String): Answer {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return Answer.Failed("not JSON")
        val errors = (root["errors"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.text("message") }
            .orEmpty()
        val data = root["data"] as? JsonObject
        if (data == null) {
            val reason = errors.joinToString("; ").ifBlank { "no data" }
            return Answer.Failed(
                reason,
                staleHash = errors.any { it.contains("PersistedQueryNotFound", ignoreCase = true) },
            )
        }
        val canvas = (data["trackUnion"] as? JsonObject)?.get("canvas") as? JsonObject
            ?: return Answer.NoCanvas("canvas null")
        val detail = "type=${canvas.text("type")} fileId=${canvas.text("fileId")} url=${canvas.text("url")}"
        val url = canvas.text("url")?.takeIf { it.startsWith("https://") }
            ?: return Answer.NoCanvas(detail)
        if (!VIDEO_URL.containsMatchIn(url)) return Answer.NoCanvas(detail)
        return Answer.Found(url, canvas.text("type"))
    }

    private val VIDEO_URL = Regex("""\.mp4(?:[?#]|$)""", RegexOption.IGNORE_CASE)
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}
