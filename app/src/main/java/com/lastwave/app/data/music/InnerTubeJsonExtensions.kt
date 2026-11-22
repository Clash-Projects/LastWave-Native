package com.lastwave.app.data.music

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.lastwave.app.data.local.AppLanguage
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.appLocale
import com.lastwave.app.data.generate.normalizeTrackText
import com.lastwave.app.data.podcast.PodcastEpisode
import com.lastwave.app.data.podcast.PodcastEpisodePage
import com.lastwave.app.data.podcast.PodcastParser
import com.lastwave.app.data.podcast.PodcastShelf
import com.lastwave.app.data.podcast.PodcastShowPage
import java.util.Locale
import com.lastwave.app.data.music.potoken.BotGuardTokenGenerator
import com.lastwave.app.data.music.potoken.PoTokenResult
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.ytmusic.YtConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** googlevideo tags the Stable-volume variant as `xtags=...drc=1...` (URL-encoded). */
internal fun isDrcAudioUrl(url: String): Boolean {
    val xtags = url.substringAfter("xtags=", "").substringBefore('&')
    if (xtags.isEmpty()) return false
    return runCatching { java.net.URLDecoder.decode(xtags, "UTF-8") }.getOrDefault(xtags).contains("drc=1")
}
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.obj(key)
internal fun JsonElement.array(key: String): JsonArray? = (this as? JsonObject)?.array(key)
internal fun JsonElement.string(key: String): String? = (this as? JsonObject)?.string(key)
internal fun JsonElement.int(key: String): Int? = (this as? JsonObject)?.int(key)

internal fun collectObjects(element: JsonElement, key: String, output: MutableList<JsonObject>) {
    when (element) {
        is JsonObject -> element.forEach { (name, child) ->
            if (name == key && child is JsonObject) output += child
            collectObjects(child, key, output)
        }
        is JsonArray -> element.forEach { collectObjects(it, key, output) }
        else -> Unit
    }
}

internal fun isSuggestionItem(renderer: JsonObject): Boolean {
    val editEndpoints = mutableListOf<JsonObject>()
    collectObjects(renderer, "playlistEditEndpoint", editEndpoints)
    if (editEndpoints.isNotEmpty()) {
        val hasAdd = editEndpoints.any { ep ->
            ep.array("actions")?.any { act ->
                val a = act.asObject()?.string("action").orEmpty()
                a.contains("ACTION_ADD", ignoreCase = true) || a.contains("ADD_VIDEO", ignoreCase = true)
            } == true
        }
        val hasRemove = editEndpoints.any { ep ->
            ep.array("actions")?.any { act ->
                val a = act.asObject()?.string("action").orEmpty()
                a.contains("ACTION_REMOVE", ignoreCase = true) || a.contains("REMOVE_VIDEO", ignoreCase = true)
            } == true
        }
        if (hasAdd && !hasRemove) return true
    }
    return false
}

internal fun isSuggestionShelf(shelf: JsonObject): Boolean {
    val targetId = shelf.string("targetId").orEmpty()
    if (targetId.contains("suggestion", ignoreCase = true)) return true

    val titleText = shelf.obj("title")?.array("runs")?.joinToString("") {
        it.asObject()?.string("text").orEmpty()
    } ?: shelf.obj("title")?.string("simpleText")
      ?: shelf.obj("header")?.obj("musicShelfHeaderRenderer")?.obj("title")?.array("runs")?.joinToString("") {
          it.asObject()?.string("text").orEmpty()
      }
      ?: shelf.obj("header")?.obj("musicShelfHeaderRenderer")?.obj("title")?.string("simpleText")
      ?: shelf.obj("header")?.obj("musicHeaderRenderer")?.obj("title")?.array("runs")?.joinToString("") {
          it.asObject()?.string("text").orEmpty()
      }
    if (!titleText.isNullOrBlank()) {
        val lower = titleText.trim().lowercase()
        if (lower.contains("suggestion") || lower.contains("suggested") ||
            lower.contains("recommend") || lower.contains("vorschl") ||
            lower.contains("empfehl") || lower.contains("sugeren") ||
            lower.contains("sugest") || lower.contains("recommand") ||
            lower.contains("sugger") || lower.contains("aanbevel") ||
            lower.contains("förslag") || lower.contains("forslag") ||
            lower.contains("suosituk") || lower.contains("рекомендац") ||
            lower.contains("öneri") || lower.contains("सुझाव") ||
            lower.contains("建议") || lower.contains("추천") ||
            lower.contains("おすすめ") || lower.contains("اقتراح")
        ) {
            return true
        }
    }
    val bottomEndpoint = shelf.obj("bottomEndpoint")
    if (bottomEndpoint != null) {
        val endpointStr = bottomEndpoint.toString().lowercase()
        if (endpointStr.contains("suggestion") || endpointStr.contains("suggested")) {
            return true
        }
    }
    val contents = shelf.array("contents") ?: shelf.array("items")
    if (contents != null && contents.isNotEmpty()) {
        val songItems = contents.mapNotNull { it.asObject()?.obj("musicResponsiveListItemRenderer") ?: it.asObject() }
            .filter { it.obj("playlistItemData") != null || it.array("flexColumns") != null }
        if (songItems.isNotEmpty() && songItems.all(::isSuggestionItem)) {
            return true
        }
    }
    return false
}
