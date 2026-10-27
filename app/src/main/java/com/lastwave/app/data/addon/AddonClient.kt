package com.lastwave.app.data.addon

import android.util.Log
import com.lastwave.app.data.lossless.NativeSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client for speaking to an Addon server over HTTP.
 *
 * Implements the standard Addon HTTP protocol:
 *  - /manifest.json
 *  - /search?q=...&quality=...&atmos=...
 *  - /stream/{id}?quality=...&atmos=...
 */
class AddonClient(
    rawBaseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val clientSecret: String? = null,
    private val nativeSecrets: NativeSecrets? = null,
) {
    val baseUrl: String = normalizeBase(rawBaseUrl)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun signRequest(urlStr: String, method: String, reqBuilder: Request.Builder) {
        // 1. Try native in-memory signing first (never exposes secret to Java memory or strings)
        val nativeSigned = nativeSecrets?.signAddonRequest(urlStr, method)
        if (nativeSigned != null) {
            reqBuilder.header("X-LW-TS", nativeSigned.first)
            reqBuilder.header("X-LW-Sign", nativeSigned.second)
            return
        }

        // 2. Fallback to Java HMAC if clientSecret provided or default known secret
        val secret = clientSecret?.takeIf { it.isNotBlank() } ?: DEFAULT_ADDON_SECRET
        try {
            val uri = java.net.URI(urlStr)
            val path = uri.path ?: "/"
            val tokenMatch = Regex("""/a/([^/]+)""").find(path)
            val token = tokenMatch?.groupValues?.get(1) ?: return
            val ts = (System.currentTimeMillis() / 1000L).toString()
            val message = "$ts\n${method.uppercase()}\n$path\n$token"
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val sign = mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            reqBuilder.header("X-LW-TS", ts)
            reqBuilder.header("X-LW-Sign", sign)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sign addon request: ${e.message}")
        }
    }

    suspend fun manifest(): Result<AddonManifest> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty addon URL"))
        val url = if (baseUrl.endsWith("/manifest.json")) baseUrl else "$baseUrl/manifest.json"
        try {
            val reqBuilder = Request.Builder().url(url).get()
            signRequest(url, "GET", reqBuilder)
            val req = reqBuilder.build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Manifest request failed (HTTP ${res.code})"))
                }
                val body = res.body?.string().orEmpty()
                val manifest = json.decodeFromString<AddonManifest>(body)
                Result.success(manifest)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun probeSearch(): Result<Int> = withContext(Dispatchers.IO) {
        search("test", "lossless", false).map { it.size }
    }

    suspend fun search(query: String, quality: String, atmos: Boolean = false): Result<List<AddonTrack>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty addon URL"))
        val q = query.trim()
        if (q.isBlank()) return@withContext Result.success(emptyList())

        val encodedQ = URLEncoder.encode(q, "UTF-8")
        val atmosParam = if (atmos) "auto" else "none"
        val url = "$baseUrl/search?q=$encodedQ&quality=$quality&atmos=$atmosParam"

        try {
            val reqBuilder = Request.Builder().url(url).get()
            signRequest(url, "GET", reqBuilder)
            val req = reqBuilder.build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Search failed (HTTP ${res.code})"))
                }
                val body = res.body?.string().orEmpty()
                val rootElement = runCatching { json.parseToJsonElement(body) }.getOrNull()
                val tracks = when (rootElement) {
                    is JsonArray -> runCatching { json.decodeFromJsonElement<List<AddonTrack>>(rootElement) }.getOrNull()
                    is JsonObject -> {
                        val tracksElement = rootElement["tracks"] ?: rootElement["data"] ?: rootElement["results"] ?: rootElement["items"]
                        if (tracksElement is JsonArray) {
                            runCatching { json.decodeFromJsonElement<List<AddonTrack>>(tracksElement) }.getOrNull()
                        } else null
                    }
                    else -> null
                } ?: json.decodeFromString<AddonSearchResponse>(body).tracks
                Result.success(tracks)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search request failed for '$query': ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun stream(
        trackId: String,
        quality: String,
        atmos: Boolean = false,
        isDownload: Boolean = false,
    ): Result<AddonStream> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty addon URL"))
        val cleanId = trackId.trim()
        if (cleanId.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty track id"))

        val atmosParam = if (atmos) "auto" else "none"
        val url = "$baseUrl/stream/$cleanId?quality=$quality&atmos=$atmosParam"

        try {
            val reqBuilder = Request.Builder().url(url).get()
            val useStreamIntent = !isDownload || atmos || quality.equals("atmos", ignoreCase = true)
            if (useStreamIntent) {
                reqBuilder.header("X-LW-Intent", "stream")
                reqBuilder.header("User-Agent", "LastWave-Player/1.0")
            } else {
                reqBuilder.header("X-LW-Intent", "download")
                reqBuilder.header("User-Agent", "LastWave-Downloader/1.0")
            }
            signRequest(url, "GET", reqBuilder)
            val req = reqBuilder.build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Stream failed (HTTP ${res.code})"))
                }
                val body = res.body?.string().orEmpty()
                val rootJson = runCatching { json.parseToJsonElement(body) }.getOrNull()
                val streamElement = (rootJson as? JsonObject)?.let { obj ->
                    obj["stream"] ?: obj["data"] ?: obj
                } ?: rootJson
                val parsed = if (streamElement != null && streamElement != rootJson) {
                    runCatching { json.decodeFromJsonElement<AddonStream>(streamElement) }
                        .getOrElse { json.decodeFromString<AddonStream>(body) }
                } else {
                    json.decodeFromString<AddonStream>(body)
                }
                val fallbackDepth = parsed.bitDepth
                    ?: extractBitDepthFromElement(streamElement)
                    ?: extractBitDepthFromElement(rootJson)
                val fallbackRate = extractSampleRateFromElement(streamElement)
                    ?: extractSampleRateFromElement(rootJson)
                    ?: parsed.sampleRate
                val finalStream = parsed.copy(
                    rawBitDepth = (fallbackDepth ?: parsed.bitDepth)?.let { JsonPrimitive(it) } ?: parsed.rawBitDepth,
                    rawSampleRate = (fallbackRate.takeIf { it > 0.0 } ?: parsed.sampleRate).let { JsonPrimitive(it) },
                )
                Result.success(finalStream)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream request failed for '$trackId': ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun health(): AddonHealth = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext AddonHealth.Rejected("Addon URL cannot be empty")
        manifest().fold(
            onSuccess = { m ->
                val info = listOfNotNull(
                    m.displayName.takeIf { it.isNotBlank() },
                    m.version.takeIf { it.isNotBlank() }?.let { "v$it" }
                ).joinToString(" ").ifBlank { null }
                AddonHealth.Ok(info)
            },
            onFailure = { err ->
                if (probeSearch().isSuccess) {
                    AddonHealth.Ok("Connected (search active)")
                } else {
                    AddonHealth.Unreachable(err.message ?: "Could not connect to addon")
                }
            }
        )
    }

    companion object {
        private const val TAG = "AddonClient"
        private const val DEFAULT_ADDON_SECRET = "36d96a751b12ee481c281a8a8e64c482d0c1634a22061ab72f40175017818b85"

        fun normalizeBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.endsWith("/manifest.json", ignoreCase = true)) {
                trimmed.substringBeforeLast("/manifest.json").trimEnd('/')
            } else {
                trimmed
            }
        }
    }
}
