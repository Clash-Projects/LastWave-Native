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
                val reqBody = json.encodeToString(ClientTokenRequest())
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(CLIENT_TOKEN_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(reqBody)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Client token request failed with HTTP ${response.code}")
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
     * Retrieves an active Spotify personal access token using sp_dc and TOTP handshake,
     * refreshing if expired or missing.
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
                // 1. Fetch secret dictionary for TOTP
                val secretsRequest = Request.Builder()
                    .url(SECRETS_DICT_URL)
                    .header("User-Agent", USER_AGENT)
                    .build()

                val secretsMap: Map<String, List<Int>> = okHttpClient.newCall(secretsRequest).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.w(TAG, "Failed to fetch secretDict: HTTP ${res.code}")
                        return@withContext cached
                    }
                    val body = res.body?.string().orEmpty()
                    json.decodeFromString(body)
                }

                val latestVersion = secretsMap.keys.maxByOrNull { it.toIntOrNull() ?: 0 } ?: return@withContext cached
                val cipherBytes = secretsMap[latestVersion] ?: return@withContext cached

                // 2. Derive secret key via XOR transformation
                val secretKeyString = buildString {
                    for ((index, byteVal) in cipherBytes.withIndex()) {
                        val decoded = byteVal xor ((index % 33) + 9)
                        append(decoded.toString())
                    }
                }
                val secretKeyBytes = secretKeyString.toByteArray(Charsets.US_ASCII)

                // 3. Fetch server time
                val serverTimeRequest = Request.Builder()
                    .url(SERVER_TIME_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "sp_dc=$spdc")
                    .build()

                val serverTimeSeconds = okHttpClient.newCall(serverTimeRequest).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string().orEmpty()
                        json.decodeFromString<SpotifyServerTimeResponse>(body).serverTime
                    } else null
                } ?: (System.currentTimeMillis() / 1000L)

                // 4. Compute TOTP (HMAC-SHA1, 30s period, 6 digits)
                val counter = serverTimeSeconds / 30L
                val counterBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array()

                val mac = Mac.getInstance("HmacSHA1")
                mac.init(SecretKeySpec(secretKeyBytes, "HmacSHA1"))
                val hmacResult = mac.doFinal(counterBytes)

                val offset = hmacResult[hmacResult.size - 1].toInt() and 0x0F
                val binary = ((hmacResult[offset].toInt() and 0x7F) shl 24) or
                    ((hmacResult[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hmacResult[offset + 2].toInt() and 0xFF) shl 8) or
                    (hmacResult[offset + 3].toInt() and 0xFF)

                val otp = (binary % 1_000_000).toString().padStart(6, '0')

                // 5. Exchange for personal access token
                val tokenUrl = "$SESSION_TOKEN_URL?reason=init&productType=mobile-web-player" +
                    "&totp=$otp&totpServer=$otp&totpVer=$latestVersion"

                val tokenRequest = Request.Builder()
                    .url(tokenUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Cookie", "sp_dc=$spdc")
                    .build()

                okHttpClient.newCall(tokenRequest).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.w(TAG, "Personal token exchange failed with HTTP ${res.code}")
                        return@withContext cached
                    }
                    val body = res.body?.string().orEmpty()
                    val tokenResp = json.decodeFromString<SpotifyPersonalTokenResponse>(body)
                    val accessToken = tokenResp.accessToken
                    if (!accessToken.isNullOrBlank()) {
                        val expiresMs = tokenResp.accessTokenExpirationTimestampMs
                            ?: (System.currentTimeMillis() + 3600_000L)
                        spotifyPreferences.savePersonalToken(accessToken, expiresMs)
                        Log.d(TAG, "Acquired fresh Spotify personal token (expires in ${(expiresMs - System.currentTimeMillis()) / 1000}s)")
                        return@withContext accessToken
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed personal token handshake: ${e.message}")
            }
            cached
        }
    }

    /**
     * Saves a track to the user's Spotify Liked Songs library.
     */
    suspend fun saveTrackToLiked(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val token = getPersonalToken() ?: return@withContext false
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/tracks?ids=$trackId")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .put("".toRequestBody(null))
                .build()
            okHttpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Removes a track from the user's Spotify Liked Songs library.
     */
    suspend fun removeTrackFromLiked(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val token = getPersonalToken() ?: return@withContext false
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/tracks?ids=$trackId")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .delete("".toRequestBody(null))
                .build()
            okHttpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        private const val TAG = "SpotifyAuthRepo"
        private const val EXPIRY_GRACE_MS = 60_000L // Refresh 1 minute before expiry

        private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
        private const val SECRETS_DICT_URL = "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"
        private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
        private const val SESSION_TOKEN_URL = "https://open.spotify.com/api/token"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
