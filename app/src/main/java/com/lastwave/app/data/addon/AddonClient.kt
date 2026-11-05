package com.lastwave.app.data.addon

import android.util.Log
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.lossless.NativeSecrets
import kotlinx.coroutines.CancellationException
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
        Log.d(TAG, "Native addon signing unavailable; using fallback signer")

        // 2. Fallback to obfuscated client secret decoded in memory at runtime (Desktop approach)
        val secret = clientSecret?.takeIf { it.isNotBlank() }
            ?: runCatching {
                decodeSecretBytes(BuildConfig.ADDON_CLIENT_SECRET_BYTES, BuildConfig.SECRET_MASK_BYTES)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return
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
            Log.w(TAG, "Failed to sign addon request (${e.javaClass.simpleName})")
        }
    }

    suspend fun manifest(): Result<AddonManifest> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty addon URL"))
        val url = if (baseUrl.endsWith("/manifest.json")) baseUrl else "$baseUrl/manifest.json"
        try {
            val reqBuilder = Request.Builder().url(url).get()
            signRequest(url, "GET", reqBuilder)
            val req = reqBuilder.build()
            client.newCall(req).awaitAddonResponse().let { res ->
                Log.i("LastWaveAddonHttp", "operation=manifest elapsedMs=${res.elapsedMs} cache=${res.cacheState ?: "UNKNOWN"} timings=${res.timingMs}")
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Manifest request failed (HTTP ${res.code})"))
                }
                val body = res.body
                val manifest = json.decodeFromString<AddonManifest>(body)
                Result.success(manifest)
            }
        } catch (e: CancellationException) {
            throw e
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
            client.newCall(req).awaitAddonResponse().let { res ->
                Log.i("LastWaveAddonHttp", "operation=search elapsedMs=${res.elapsedMs} cache=${res.cacheState ?: "UNKNOWN"} timings=${res.timingMs}")
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Search failed (HTTP ${res.code})"))
                }
                val body = res.body
                val rootElement = runCatching { json.parseToJsonElement(body) }.getOrNull()
                val tracks = when (rootElement) {
                    is JsonArray -> decodeTracks(rootElement)
                    is JsonObject -> {
                        val tracksElement = rootElement["tracks"] ?: rootElement["data"] ?: rootElement["results"] ?: rootElement["items"]
                        if (tracksElement is JsonArray) decodeTracks(tracksElement) else null
                    }
                    else -> null
                } ?: json.decodeFromString<AddonSearchResponse>(body).tracks
                Result.success(tracks)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search request failed (${e.javaClass.simpleName})")
            Result.failure(e)
        }
    }

    /** One malformed entry must not discard every other result. */
    private fun decodeTracks(items: JsonArray): List<AddonTrack> =
        items.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement<AddonTrack>(item) }.getOrNull()
                ?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
        }

    /**
     * Asks the addon to prepare (not return) the plan `/stream` would build for
     * this exact id/tier, so the real play request is a cache hit.
     *
     * ## Addon contract (optional route — for addon developers)
     *
     * `GET {base}/prewarm/{id}?quality={atmos|hi_res|lossless|high|low}&atmos={auto|none}`
     *
     * - `{id}` and the query use exactly the values the app will later send to
     *   `GET {base}/stream/{id}`; the app picks them with the same matcher it
     *   plays with, so implementers should prepare *that* id/tier, not their own
     *   top search hit.
     * - Signed like `/stream` (`X-LW-TS` / `X-LW-Sign`) when client auth is on.
     * - Must return immediately and do the work in the background:
     *   `202 {"state":"started"|"duplicate"}` — queued, or already preparing/cached;
     *   `503 {"state":"busy"}` — background capacity full, nothing queued;
     *   `429 {"state":"quota"}` — the token cannot play today, nothing queued;
     *   `400` invalid id, `403` client auth failed, `404` route not supported.
     * - Must be FREE: never consume play quota and never return the plan, key
     *   or media URLs. The later `/stream` call is what charges, and should
     *   join an in-flight preparation (single-flight) or read the cached plan.
     * - Should cap concurrent background preparations (reference addon: 4) and
     *   validate `{id}` before any upstream request.
     *
     * Every failure here is silent: the app treats anything but 202 as "not
     * warmed" and plays normally, so addons without the route lose nothing.
     * Reference implementation: amz-addon `src/addon/routes.ts` (`handlePrewarm`).
     *
     * @return true only when the addon accepted the warm-up (HTTP 202).
     */
    suspend fun prewarm(trackId: String, quality: String, atmos: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cleanId = trackId.trim()
        if (baseUrl.isBlank() || cleanId.isBlank()) return@withContext false
        val url = "$baseUrl/prewarm/$cleanId?quality=$quality&atmos=${if (atmos) "auto" else "none"}"
        try {
            val reqBuilder = Request.Builder().url(url).get()
            signRequest(url, "GET", reqBuilder)
            client.newCall(reqBuilder.build()).awaitAddonResponse().let { res ->
                Log.i("LastWaveAddonHttp", "operation=prewarm elapsedMs=${res.elapsedMs} status=${res.code}")
                res.code == 202
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
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
            client.newCall(req).awaitAddonResponse().let { res ->
                Log.i("LastWaveAddonHttp", "operation=stream elapsedMs=${res.elapsedMs} cache=${res.cacheState ?: "UNKNOWN"} timings=${res.timingMs}")
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Stream failed (HTTP ${res.code})"))
                }
                val body = res.body
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stream request failed (${e.javaClass.simpleName})")
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

        fun decodeSecretBytes(data: ByteArray, mask: ByteArray): String {
            if (data.isEmpty() || mask.isEmpty()) return ""
            val decoded = ByteArray(data.size) { i ->
                (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            return String(decoded, Charsets.UTF_8).trim()
        }

        fun normalizeBase(raw: String): String {
            if (raw.isBlank()) return ""
            // 1. Strip all ASCII whitespace, Unicode invisible / zero-width characters and BOM
            val sanitized = raw
                .replace(Regex("""[\u200B-\u200D\uFEFF\u00A0\u200E\u200F\u0000-\u001F]"""), "")
                .trim()
                .trimEnd('/')

            if (sanitized.isBlank()) return ""

            // 2. Ensure scheme is present (default to https:// if omitted by user)
            val withScheme = if (!sanitized.startsWith("http://", ignoreCase = true) &&
                !sanitized.startsWith("https://", ignoreCase = true)
            ) {
                "https://$sanitized"
            } else {
                sanitized
            }

            // 3. Strip query parameters and hash fragments
            val withoutQuery = withScheme.substringBefore('?').substringBefore('#').trimEnd('/')

            // 4. Strip known trailing endpoints (e.g. /manifest.json, /manifest, /search, /stream)
            val knownSuffixes = listOf(
                "/manifest.json",
                "/manifest",
                "/search",
                "/stream",
            )
            var cleanUrl = withoutQuery
            for (suffix in knownSuffixes) {
                if (cleanUrl.endsWith(suffix, ignoreCase = true)) {
                    cleanUrl = cleanUrl.substring(0, cleanUrl.length - suffix.length).trimEnd('/')
                    break
                }
            }

            return cleanUrl
        }
    }
}
