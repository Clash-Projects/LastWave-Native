package com.lastwave.app.data.tidal

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lastwave.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class TidalSession(
    val userId: Long,
    val countryCode: String,
    val username: String,
)

/**
 * The user's own TIDAL account, signed in with TIDAL's PKCE web login (the
 * flow python-tidal uses for hi-res). Tokens live in app-private prefs and are
 * refreshed on demand; every catalog/stream call goes through [get].
 */
@Singleton
class TidalAccount @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("tidal_account", Context.MODE_PRIVATE)
    private val http = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val refreshLock = Mutex()

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<TidalSession?> = _session.asStateFlow()

    val isLoggedIn: Boolean get() = _session.value != null

    val hasClientCredentials: Boolean get() = BuildConfig.TIDAL_CLIENT_ID.isNotBlank()

    /** Verifier of the login currently shown; pairs with the code the redirect brings back. */
    @Volatile
    private var pendingVerifier: String? = null
    private val clientUniqueKey: String by lazy {
        prefs.getString(KEY_UNIQUE, null) ?: randomHex(8).also { prefs.edit().putString(KEY_UNIQUE, it).apply() }
    }

    fun loginUrl(): String {
        val verifier = Base64.encodeToString(randomBytes(32), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        pendingVerifier = verifier
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("client_id", BuildConfig.TIDAL_CLIENT_ID)
            .appendQueryParameter("lang", "EN")
            .appendQueryParameter("appMode", "android")
            .appendQueryParameter("client_unique_key", clientUniqueKey)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("restrict_signup", "true")
            .build()
            .toString()
    }

    fun isRedirect(url: String): Boolean = url.startsWith(REDIRECT_URI)

    /** Finishes the login from the redirect URL the web view landed on. */
    suspend fun completeLogin(redirectUrl: String): Result<TidalSession> = withContext(Dispatchers.IO) {
        runCatching {
            val code = Uri.parse(redirectUrl).getQueryParameter("code")
                ?: error("TIDAL returned no authorization code")
            val verifier = pendingVerifier ?: error("Login expired, please try again")
            val token = postToken(
                FormBody.Builder()
                    .add("code", code)
                    .add("client_id", BuildConfig.TIDAL_CLIENT_ID)
                    .add("grant_type", "authorization_code")
                    .add("redirect_uri", REDIRECT_URI)
                    .add("scope", SCOPE)
                    .add("code_verifier", verifier)
                    .add("client_unique_key", clientUniqueKey)
                    .build(),
            )
            pendingVerifier = null
            storeToken(token)
            val sessionJson = JSONObject(rawGet("$API_V1/sessions", token.getString("access_token")))
            val user = token.optJSONObject("user")
            val session = TidalSession(
                userId = sessionJson.getLong("userId"),
                countryCode = sessionJson.getString("countryCode"),
                username = user?.optString("username").orEmpty()
                    .ifBlank { user?.optString("email").orEmpty() },
            )
            prefs.edit()
                .putLong(KEY_USER_ID, session.userId)
                .putString(KEY_COUNTRY, session.countryCode)
                .putString(KEY_USERNAME, session.username)
                .apply()
            _session.value = session
            session
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    /** GET on api.tidal.com/v1 as the user; refreshes the access token once on 401. */
    suspend fun get(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val session = _session.value ?: error("Not signed in to TIDAL")
        val url = Uri.parse("$API_V1/$path").buildUpon().apply {
            params.forEach { (k, v) -> appendQueryParameter(k, v) }
            appendQueryParameter("countryCode", session.countryCode)
        }.build().toString()
        val token = validAccessToken()
        val body = try {
            rawGet(url, token)
        } catch (e: TidalHttpException) {
            if (e.code != 401) throw e
            rawGet(url, refresh(staleToken = token))
        }
        JSONObject(body)
    }

    private suspend fun validAccessToken(): String {
        val token = prefs.getString(KEY_ACCESS, null) ?: error("Not signed in to TIDAL")
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return if (System.currentTimeMillis() < expiresAt - 60_000L) token else refresh(staleToken = token)
    }

    private suspend fun refresh(staleToken: String): String = refreshLock.withLock { refreshLocked(staleToken) }

    private fun refreshLocked(staleToken: String): String {
        // Another caller may have refreshed while this one waited for the lock.
        val current = prefs.getString(KEY_ACCESS, null)
        if (current != null && current != staleToken && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES_AT, 0L)) {
            return current
        }
        val refreshToken = prefs.getString(KEY_REFRESH, null) ?: error("Not signed in to TIDAL")
        val token = try {
            postToken(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", BuildConfig.TIDAL_CLIENT_ID)
                    .add("client_secret", BuildConfig.TIDAL_CLIENT_SECRET)
                    .build(),
            )
        } catch (e: TidalHttpException) {
            // A revoked/expired refresh token cannot recover: sign out so the
            // resolver stops trying and Settings shows the login again.
            if (e.code == 400 || e.code == 401) {
                Log.w(TAG, "TIDAL refresh token rejected; signing out")
                logout()
            }
            throw e
        }
        storeToken(token)
        return token.getString("access_token")
    }

    private fun storeToken(token: JSONObject) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS, token.getString("access_token"))
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000L)
        // Refresh responses omit refresh_token; keep the existing one.
        token.optString("refresh_token").takeIf { it.isNotBlank() }?.let { editor.putString(KEY_REFRESH, it) }
        editor.apply()
    }

    private fun postToken(form: FormBody): JSONObject {
        val request = Request.Builder().url(TOKEN_URL).post(form).build()
        http.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw TidalHttpException(res.code, body.take(200))
            return JSONObject(body)
        }
    }

    private fun rawGet(url: String, accessToken: String): String {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw TidalHttpException(res.code, body.take(200))
            return body
        }
    }

    private fun loadSession(): TidalSession? {
        if (prefs.getString(KEY_REFRESH, null).isNullOrBlank()) return null
        val country = prefs.getString(KEY_COUNTRY, null) ?: return null
        return TidalSession(
            userId = prefs.getLong(KEY_USER_ID, 0L),
            countryCode = country,
            username = prefs.getString(KEY_USERNAME, null).orEmpty(),
        )
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun randomHex(n: Int) = randomBytes(n).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "TidalAccount"
        private const val AUTHORIZE_URL = "https://login.tidal.com/authorize"
        private const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
        private const val REDIRECT_URI = "https://tidal.com/android/login/auth"
        private const val API_V1 = "https://api.tidal.com/v1"
        private const val SCOPE = "r_usr w_usr w_sub"

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_COUNTRY = "country_code"
        private const val KEY_USERNAME = "username"
        private const val KEY_UNIQUE = "client_unique_key"
    }
}

class TidalHttpException(val code: Int, body: String) : Exception("HTTP $code: $body")
