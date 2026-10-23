package com.lastwave.app.data.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyAuthRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val spotifyPreferences: SpotifyPreferences,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val clientTokenMutex = Mutex()
    private val personalTokenMutex = Mutex()

    /**
     * Retrieves an active Spotify client token, refreshing if expired or missing.
     */
    suspend fun getClientToken(): String? = clientTokenMutex.withLock {
        withContext(Dispatchers.IO) {
            val cached = spotifyPreferences.getClientToken()
            val expiresAt = spotifyPreferences.getClientTokenExpires()
            val now = System.currentTimeMillis()

            if (!cached.isNullOrBlank() && now < expiresAt - EXPIRY_GRACE_MS) {
                return@withContext cached
            }

            try {
                // The profile has to describe one browser, and the user agent has to be the
                // browser it describes: an endpoint that is told "macos" by the body and
                // "Windows" by the header is being handed two different machines.
                val deviceId = spotifyPreferences.getOrCreateDeviceId()
                val reqBytes = json.encodeToString(
                    ClientTokenRequest(
                        client_data = ClientTokenRequest.ClientData(
                            client_version = SPOTIFY_APP_VERSION,
                            client_id = SPOTIFY_CLIENT_ID,
                            js_sdk_data = ClientTokenRequest.JsSdkData(
                                device_brand = "Apple",
                                device_model = "unknown",
                                os = "macos",
                                os_version = "10.15.7",
                                device_id = deviceId,
                                device_type = "computer",
                            ),
                        ),
                    ),
                ).toByteArray(Charsets.UTF_8)
                val reqBody = reqBytes.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(CLIENT_TOKEN_URL)
                    .header("User-Agent", CLIENT_TOKEN_UA)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(reqBody)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = runCatching { response.body?.string() }.getOrNull()
                        Log.w(TAG, "Client token request failed with HTTP ${response.code}: $errBody")
                        return@withContext cached
                    }
                    val bodyString = response.body?.string().orEmpty()
                    val result = json.decodeFromString<ClientTokenResponse>(bodyString)
                    val token = result.granted_token?.token
                    if (!token.isNullOrBlank()) {
                        val expiresAfterSec = result.granted_token.expires_after_seconds ?: 7200L
                        val expireMs = System.currentTimeMillis() + (expiresAfterSec * 1000L)
                        spotifyPreferences.saveClientToken(token, expireMs)
                        Log.d(TAG, "Acquired fresh Spotify client token")
                        return@withContext token
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch Spotify client token: ${e.message}")
            }
            cached
        }
    }

    /**
     * Retrieves an active Spotify personal access token, refreshing if expired or missing.
     *
     * Two reasons are asked for in turn. `transport` is the one the web player itself uses
     * and normally answers with a full session token; `init` is the fallback for when it
     * answers with a short placeholder instead. A token that says the session is anonymous
     * means the stored cookie is no longer a signed-in session, so the connection is dropped
     * instead of being cached for another hour of silent failures.
     */
    suspend fun getPersonalToken(): String? = personalTokenMutex.withLock {
        withContext(Dispatchers.IO) {
            val spdc = spotifyPreferences.getSpdc()
            if (spdc.isNullOrBlank()) {
                return@withContext null
            }

            val cached = spotifyPreferences.getPersonalToken()
            val expiresAt = spotifyPreferences.getPersonalTokenExpires()
            val now = System.currentTimeMillis()

            if (!cached.isNullOrBlank() && now < expiresAt - EXPIRY_GRACE_MS) {
                return@withContext cached
            }

            try {
                val totp = computeTotp(spdc) ?: return@withContext cached

                val transport = requestPersonalToken(spdc, totp, REASON_TRANSPORT)
                val init = if (transport.isUsable()) {
                    null
                } else {
                    requestPersonalToken(spdc, totp, REASON_INIT)
                }

                val chosen = listOfNotNull(transport, init).firstOrNull { it.isUsable() }
                if (chosen == null) {
                    if (transport?.isAnonymous == true || init?.isAnonymous == true) {
                        Log.w(TAG, "Spotify session returned anonymous; stored cookie may need re-login")
                    }
                    return@withContext cached
                }

                val accessToken = chosen.accessToken.orEmpty()
                val expiresMs = chosen.accessTokenExpirationTimestampMs
                    ?: (System.currentTimeMillis() + 3600_000L)
                spotifyPreferences.savePersonalToken(accessToken, expiresMs)
                Log.d(TAG, "Acquired fresh Spotify personal token (expires in ${(expiresMs - System.currentTimeMillis()) / 1000}s)")
                accessToken
            } catch (e: Exception) {
                Log.w(TAG, "Failed personal token handshake: ${e.message}")
                cached
            }
        }
    }

    /**
     * Whether this answer carries a token worth using.
     *
     * An anonymous token is a guest session and is refused outright. A very short token is
     * the placeholder the endpoint hands back instead of a real session token, which is why
     * the length is looked at and not only the emptiness: a blank string and a stub are both
     * "no token", but only one of them looks like a token at a glance.
     */
    private fun SpotifyPersonalTokenResponse?.isUsable(): Boolean {
        val response = this ?: return false
        val token = response.accessToken ?: return false
        return response.isAnonymous != true && token.length >= MIN_PERSONAL_TOKEN_LENGTH
    }

    /**
     * The one-time passwords for client time and server time, plus the secret version.
     */
    private data class Totp(val otpClient: String, val otpServer: String, val version: Int)

    private fun generateOtp(secretKeyBytes: ByteArray, timestampSeconds: Long): String {
        val counter = timestampSeconds / 30L
        val counterBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKeyBytes, "HmacSHA1"))
        val hmacResult = mac.doFinal(counterBytes)

        val offset = hmacResult[hmacResult.size - 1].toInt() and 0x0F
        val binary = ((hmacResult[offset].toInt() and 0x7F) shl 24) or
            ((hmacResult[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmacResult[offset + 2].toInt() and 0xFF) shl 8) or
            (hmacResult[offset + 3].toInt() and 0xFF)

        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    /**
     * Derives the one-time password from the secret the web player is carrying.
     *
     * The secret is read out of the player rather than from a copy of it: a copy goes
     * stale when the player is rebuilt, and a secret that has been retired produces a
     * token request that is refused — which is indistinguishable, from the outside,
     * from a session that was never signed in.
     */
    private suspend fun computeTotp(spdc: String): Totp? {
        val secret = readSecret() ?: return null
        val version = secret.version
        val plainBytes = secret.value.map { it.code }

        val secretKeyString = buildString {
            for ((index, byteVal) in plainBytes.withIndex()) {
                val decoded = byteVal xor ((index % 33) + 9)
                append(decoded.toString())
            }
        }
        val secretKeyBytes = secretKeyString.toByteArray(Charsets.US_ASCII)

        val serverTimeRequest = Request.Builder()
            .url(SERVER_TIME_URL)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "sp_dc=$spdc")
            .header("App-platform", "WebPlayer")
            .header("Spotify-App-Version", SPOTIFY_APP_VERSION)
            .header("Origin", SPOTIFY_ORIGIN)
            .header("Referer", "$SPOTIFY_ORIGIN/")
            .header("Accept", "application/json")
            .build()

        val serverTimeSeconds = okHttpClient.newCall(serverTimeRequest).execute().use { res ->
            if (res.isSuccessful) {
                json.decodeFromString<SpotifyServerTimeResponse>(res.body?.string().orEmpty()).serverTime
            } else {
                null
            }
        } ?: (System.currentTimeMillis() / 1000L)

        val clientTimeSeconds = System.currentTimeMillis() / 1000L
        val otpClient = generateOtp(secretKeyBytes, clientTimeSeconds)
        val otpServer = generateOtp(secretKeyBytes, serverTimeSeconds)
        return Totp(otpClient, otpServer, version)
    }

    /**
     * The one-time-password secret the web player is currently carrying.
     *
     * Read from the player itself. The published copy of it is only a fallback: it is
     * written by a scraper that has stopped finding the secret, so it froze months ago
     * while the player moved on.
     */
    private suspend fun readSecret(): SpotifyWebPlayer.Secret? {
        val script = SpotifyWebPlayer.script(okHttpClient)
        val fromPlayer = script?.let { SpotifyWebPlayer.secrets(it).firstOrNull() }
        if (fromPlayer != null) {
            Log.d(TAG, "One-time password secret read from the web player (version ${fromPlayer.version})")
            return fromPlayer
        }
        return readPublishedSecret()
    }

    /**
     * The published copy of the secret, used only when the player could not be read.
     */
    private suspend fun readPublishedSecret(): SpotifyWebPlayer.Secret? {
        val secretsRequest = Request.Builder()
            .url(SECRETS_DICT_URL)
            .header("User-Agent", USER_AGENT)
            .build()

        val secretsMap: Map<String, List<Int>> = okHttpClient.newCall(secretsRequest).execute().use { res ->
            if (!res.isSuccessful) {
                Log.w(TAG, "Failed to fetch secretDict: HTTP ${res.code}")
                return null
            }
            json.decodeFromString(res.body?.string().orEmpty())
        }

        val latestVersion = secretsMap.keys.maxByOrNull { it.toIntOrNull() ?: 0 } ?: return null
        val cipherBytes = secretsMap[latestVersion] ?: return null
        val version = latestVersion.toIntOrNull() ?: return null
        Log.d(TAG, "Falling back to the published one-time password secret (version $version)")
        return SpotifyWebPlayer.Secret(String(cipherBytes.map { it.toChar() }.toCharArray()), version)
    }

    /**
     * Asks for a personal token for one reason.
     */
    private suspend fun requestPersonalToken(
        spdc: String,
        totp: Totp,
        reason: String,
    ): SpotifyPersonalTokenResponse? {
        val tokenUrl = "$SESSION_TOKEN_URL?reason=$reason&productType=web-player" +
            "&totp=${totp.otpClient}&totpServer=${totp.otpServer}&totpVer=${totp.version}"

        val tokenRequest = Request.Builder()
            .url(tokenUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Cookie", "sp_dc=$spdc")
            .header("App-platform", "WebPlayer")
            .header("Spotify-App-Version", SPOTIFY_APP_VERSION)
            .header("Origin", SPOTIFY_ORIGIN)
            .header("Referer", "$SPOTIFY_ORIGIN/")
            .build()

        return okHttpClient.newCall(tokenRequest).execute().use { res ->
            if (!res.isSuccessful) {
                Log.w(TAG, "Personal token exchange ($reason) failed with HTTP ${res.code}")
                return@use null
            }
            runCatching {
                json.decodeFromString<SpotifyPersonalTokenResponse>(res.body?.string().orEmpty())
            }.getOrNull()
        }
    }

    private companion object {
        private const val TAG = "SpotifyAuthRepo"
        private const val EXPIRY_GRACE_MS = 60_000L // Refresh 1 minute before expiry

        private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
        private const val SECRETS_DICT_URL = "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"
        private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
        private const val SESSION_TOKEN_URL = "https://open.spotify.com/api/token"

        private const val REASON_TRANSPORT = "transport"
        private const val REASON_INIT = "init"

        /** A real session token is a long opaque string; a stub is not. */
        private const val MIN_PERSONAL_TOKEN_LENGTH = 100

        private const val SPOTIFY_ORIGIN = "https://open.spotify.com"
        private const val SPOTIFY_APP_VERSION = "1.2.62.476.g2ad6e7f3"
        private const val SPOTIFY_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val CLIENT_TOKEN_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    }
}
