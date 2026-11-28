package com.lastwave.app.data.scrobbler

import android.util.Log
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.network.LastFmSigner
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class Listen(
    val artist: String,
    val track: String,
    val album: String? = null,
    val startedAtSec: Long = 0L,
) {
    internal val trackKey get() = listOf(artist, track).map { it.trim().lowercase(Locale.ROOT) }
    internal val submissionKey get() = trackKey to startedAtSec
}

internal fun normalizeServer(raw: String, fallback: String): String {
    val value = raw.trim().ifEmpty { fallback }
    val url = (if (value.contains("://")) value else "https://$value").toHttpUrlOrNull()
    require(url != null && url.username.isEmpty() && url.password.isEmpty() &&
        url.query == null && url.fragment == null) { "Enter a valid server address." }
    return url.toString().trimEnd('/')
}

private val json = Json { ignoreUnknownKeys = true }
private val USER_AGENT = "SoundDrop/${BuildConfig.VERSION_NAME} (https://sounddrop.framer.website)"
private const val TAG = "ExtraScrobblers"

private data class ApiResponse(val code: Int, val body: JsonObject?, val retryAfterMs: Long = 0L) {
    val successful get() = code in 200..299
}

private suspend fun OkHttpClient.send(request: Request): ApiResponse? = suspendCancellableCoroutine { continuation ->
    val call = newCall(request.newBuilder().header("User-Agent", USER_AGENT).build())
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resume(null)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = response.use {
                val body = try {
                    json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
                } catch (_: Exception) {
                    null
                }
                val limited = it.code == 429 || it.header("X-RateLimit-Remaining") == "0"
                val retryAfter = if (limited) {
                    (it.header("X-RateLimit-Reset-In") ?: it.header("Retry-After"))
                        ?.toLongOrNull()?.coerceIn(0L, 86_400L)?.times(1_000L) ?: 0L
                } else 0L
                ApiResponse(it.code, body, retryAfter)
            }
            if (continuation.isActive) continuation.resume(result)
        }
    })
}

private suspend fun <T> serviceResult(fallback: (Exception) -> T, block: suspend () -> T): T = try {
    block()
} catch (error: Exception) {
    currentCoroutineContext().ensureActive()
    fallback(error)
}

interface ExtraScrobbler {
    val service: ExtraService
    suspend fun nowPlaying(listen: Listen, account: ScrobblerAccount): Boolean
    suspend fun submit(listen: Listen, account: ScrobblerAccount): Boolean
}

@Singleton
class ListenBrainzClient @Inject constructor(
    private val http: OkHttpClient,
    private val prefs: ExtraScrobblerPreferences,
) : ExtraScrobbler {
    override val service = ExtraService.LISTENBRAINZ
    private val callMutex = Mutex()
    private var nextCallAtMs = 0L

    // ListenBrainz requires at most one call per second, including queue retries.
    private suspend fun call(request: Request): ApiResponse? = callMutex.withLock {
        delay((nextCallAtMs - System.nanoTime() / 1_000_000).coerceAtLeast(0L))
        val response = http.send(request)
        nextCallAtMs = System.nanoTime() / 1_000_000 + maxOf(1_000L, response?.retryAfterMs ?: 0L)
        response
    }

    suspend fun connect(token: String, server: String): String? = serviceResult(
        { if (it is IllegalArgumentException) "Enter a valid server address and token." else "Could not connect to ListenBrainz." },
    ) {
        val credential = token.trim()
        if (credential.isEmpty()) return@serviceResult "Paste your user token."
        val base = normalizeServer(server, DEFAULT_SERVER)
        val response = call(Request.Builder().url("$base/1/validate-token")
            .header("Authorization", "Token $credential").cacheControl(CacheControl.FORCE_NETWORK).build())
            ?: return@serviceResult "Could not reach $base."
        if (!response.successful || (response.body?.get("valid") as? JsonPrimitive)?.booleanOrNull != true) {
            return@serviceResult "The token was not accepted."
        }
        prefs.save(service, ScrobblerAccount(credential,
            (response.body?.get("user_name") as? JsonPrimitive)?.contentOrNull.orEmpty(), server.trim()))
        null
    }

    override suspend fun nowPlaying(listen: Listen, account: ScrobblerAccount) = post("playing_now", listen, account)
    override suspend fun submit(listen: Listen, account: ScrobblerAccount) = post("single", listen, account)

    private suspend fun post(type: String, listen: Listen, account: ScrobblerAccount): Boolean = serviceResult({ false }) {
        if (!account.connected) return@serviceResult false
        val payload = buildJsonObject {
            put("listen_type", type)
            put("payload", buildJsonArray {
                add(buildJsonObject {
                    if (type == "single") put("listened_at", listen.startedAtSec)
                    put("track_metadata", buildJsonObject {
                        put("artist_name", listen.artist)
                        put("track_name", listen.track)
                        if (!listen.album.isNullOrBlank()) put("release_name", listen.album)
                        put("additional_info", buildJsonObject {
                            put("media_player", "SoundDrop")
                            put("submission_client", "SoundDrop Android")
                            put("submission_client_version", BuildConfig.VERSION_NAME)
                        })
                    })
                })
            })
        }
        val response = call(Request.Builder().url("${normalizeServer(account.server, DEFAULT_SERVER)}/1/submit-listens")
            .header("Authorization", "Token ${account.credential}")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build())
        val ok = response?.successful == true
        if (!ok) Log.i(TAG, "listenbrainz $type http=${response?.code ?: 0}")
        // The single-listen payload cannot become valid by retrying unchanged metadata.
        ok || (type == "single" && response?.code == 400)
    }

    companion object { const val DEFAULT_SERVER = "https://api.listenbrainz.org" }
}

@Singleton
class LibreFmClient @Inject constructor(
    private val http: OkHttpClient,
    private val prefs: ExtraScrobblerPreferences,
) : ExtraScrobbler {
    override val service = ExtraService.LIBREFM

    private suspend fun call(base: String, params: Map<String, String>): ApiResponse? {
        val signed = params + ("api_key" to API_KEY)
        val form = FormBody.Builder().apply {
            (signed + ("api_sig" to LastFmSigner.sign(signed, API_SECRET)) + ("format" to "json"))
                .forEach { (key, value) -> add(key, value) }
        }.build()
        return http.send(Request.Builder().url("$base/2.0/").post(form).build())
    }

    suspend fun connect(user: String, password: String, server: String): String? = serviceResult(
        { if (it is IllegalArgumentException) "Enter a valid server address." else "Could not connect to Libre.fm." },
    ) {
        val username = user.trim()
        if (username.isEmpty() || password.isEmpty()) return@serviceResult "Enter your username and password."
        val base = normalizeServer(server, DEFAULT_SERVER)
        val response = call(base, mapOf(
            "method" to "auth.getMobileSession",
            "username" to username,
            "authToken" to LastFmSigner.md5(username.lowercase(Locale.ROOT) + LastFmSigner.md5(password)),
        )) ?: return@serviceResult "Could not reach $base."
        val body = response.body
        val session = body?.get("session") as? JsonObject
        val key = (session?.get("key") as? JsonPrimitive)?.contentOrNull
        if (!response.successful || key.isNullOrEmpty() || body?.containsKey("error") == true) {
            return@serviceResult (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Sign-in was not accepted."
        }
        prefs.save(service, ScrobblerAccount(key,
            (session?.get("name") as? JsonPrimitive)?.contentOrNull ?: username, server.trim()))
        null
    }

    override suspend fun nowPlaying(listen: Listen, account: ScrobblerAccount) = write("track.updateNowPlaying", account,
        mapOf("artist" to listen.artist, "track" to listen.track, "album" to listen.album.orEmpty()))

    override suspend fun submit(listen: Listen, account: ScrobblerAccount) = write("track.scrobble", account, mapOf(
        "artist[0]" to listen.artist,
        "track[0]" to listen.track,
        "album[0]" to listen.album.orEmpty(),
        "timestamp[0]" to listen.startedAtSec.toString(),
    ))

    private suspend fun write(method: String, account: ScrobblerAccount, params: Map<String, String>): Boolean = serviceResult({ false }) {
        if (!account.connected) return@serviceResult false
        val response = call(normalizeServer(account.server, DEFAULT_SERVER),
            mapOf("method" to method, "sk" to account.credential) + params.filterValues { it.isNotEmpty() })
        val body = response?.body
        if (response?.successful != true || body == null || "error" in body) {
            Log.i(TAG, "librefm $method http=${response?.code ?: 0}")
            return@serviceResult false
        }
        val result = if (method == "track.scrobble") {
            (body["scrobbles"] as? JsonObject)?.get("scrobble") as? JsonObject
        } else body["nowplaying"] as? JsonObject
        val code = ((result?.get("ignoredMessage") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        // Retrying permanently rejected metadata would block all later queued listens.
        val permanentRejection = code != null && code in 1..4
        if (permanentRejection) Log.i(TAG, "librefm $method ignored=$code")
        code == 0 || code == 91 || permanentRejection
    }

    companion object {
        const val DEFAULT_SERVER = "https://libre.fm"
        // GNU FM validates only the lengths; it cannot verify Last.fm API secrets.
        private const val API_KEY = "00000000000000000000000000000000"
        private const val API_SECRET = "00000000000000000000000000000000"
    }
}

@Singleton
class ExtraScrobblers internal constructor(
    private val prefs: ExtraScrobblerPreferences,
    private val services: List<ExtraScrobbler>,
    private val scope: CoroutineScope,
) {
    @Inject constructor(prefs: ExtraScrobblerPreferences, listenBrainz: ListenBrainzClient, libreFm: LibreFmClient) :
        this(prefs, listOf(listenBrainz, libreFm), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private class ServiceState {
        val mutex = Mutex()
        var account = ScrobblerAccount()
        val queue = ArrayDeque<Listen>()
        val submitted = linkedSetOf<Pair<List<String>, Long>>()
        var nowPlaying: Pair<Long, List<String>>? = null
    }

    private val states = services.associate { it.service to ServiceState() }
    private val nowPlayingGeneration = AtomicLong()

    init {
        services.forEach { service ->
            scope.launch {
                prefs.account(service.service).collect { account ->
                    val state = states.getValue(service.service)
                    state.mutex.withLock { updateAccount(state, account) }
                }
            }
        }
    }

    fun resetNowPlaying() { nowPlayingGeneration.incrementAndGet() }

    fun nowPlaying(listen: Listen) {
        if (listen.artist.isBlank() || listen.track.isBlank()) return
        val generation = nowPlayingGeneration.get()
        forEachConnected { service, state ->
            flush(service, state)
            val key = generation to listen.trackKey
            if (generation == nowPlayingGeneration.get() && state.nowPlaying != key && service.nowPlaying(listen, state.account)) {
                state.nowPlaying = key
            }
        }
    }

    fun submit(listen: Listen) {
        if (listen.artist.isBlank() || listen.track.isBlank() || listen.startedAtSec <= 0L) return
        forEachConnected { service, state ->
            // Last.fm retries must not enqueue or resend the same listen to a successful service.
            if (listen.submissionKey !in state.submitted && state.queue.none { it.submissionKey == listen.submissionKey }) {
                state.queue.addLast(listen)
                while (state.queue.size > MAX_QUEUE) state.queue.removeFirst()
            }
            flush(service, state)
        }
    }

    private suspend fun flush(service: ExtraScrobbler, state: ServiceState) {
        while (state.queue.isNotEmpty()) {
            val account = prefs.current(service.service)
            if (account != state.account) {
                updateAccount(state, account)
                return
            }
            val listen = state.queue.first()
            if (!service.submit(listen, account)) return
            state.queue.removeFirst()
            state.submitted.add(listen.submissionKey)
            if (state.submitted.size > MAX_QUEUE) state.submitted.remove(state.submitted.first())
        }
    }

    private fun forEachConnected(block: suspend (ExtraScrobbler, ServiceState) -> Unit) {
        services.forEach { service ->
            scope.launch {
                serviceResult({ Log.i(TAG, "${service.service.displayName} submission failed"); Unit }) {
                    val state = states.getValue(service.service)
                    state.mutex.withLock {
                        updateAccount(state, prefs.current(service.service))
                        if (state.account.connected) block(service, state)
                    }
                }
            }
        }
    }

    private fun updateAccount(state: ServiceState, account: ScrobblerAccount) {
        if (state.account == account) return
        // Pending listens belong to the old account, never to its replacement.
        state.account = account
        state.queue.clear()
        state.submitted.clear()
        state.nowPlaying = null
    }

    private companion object { const val MAX_QUEUE = 200 }
}
