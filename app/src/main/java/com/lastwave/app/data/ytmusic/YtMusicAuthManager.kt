package com.lastwave.app.data.ytmusic

import android.util.Log
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the connected YouTube Music account's session cookies and derives the
 * per-request SAPISIDHASH Authorization header that InnerTube write endpoints
 * require (same scheme music.youtube.com itself uses in the browser).
 *
 * Cookies are captured by [com.lastwave.app.ui.settings.YouTubeLoginScreen]'s
 * WebView sign-in flow and persisted via [YtMusicPreferences].
 */
@Singleton
class YtMusicAuthManager @Inject constructor(
    private val preferences: YtMusicPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow(YtConnection.DISCONNECTED)
    val connection: StateFlow<YtConnection> = _connection.asStateFlow()

    init {
        scope.launch {
            runCatching { preferences.connection.collect { _connection.value = it } }
                .onFailure { Log.w(TAG, "Failed to observe YT connection", it) }
        }
    }

    /** SAPISID token, extracted in priority order exactly like music.youtube.com. */
    fun sapisid(account: YtConnection = connection.value): String? =
        account.cookies[COOKIE_SAPISID_PRIMARY]
            ?: account.cookies["SAPISID"]
            ?: account.cookies["APISID"]

    fun cookieHeaderValue(account: YtConnection = connection.value): String? {
        val cookies = account.cookies
        if (cookies.isEmpty()) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    suspend fun awaitLoadedConnection(): YtConnection {
        val persisted = preferences.connection.first()
        return connection.first { it == persisted }
    }

    /**
     * Builds `SAPISIDHASH <unix_ts>_<sha1(ts SP sapisid SP origin)>` — the
     * reverse-engineered scheme documented at stackoverflow.com/a/32065323
     * and used by every InnerTube client with credentials.
     */
    fun authorizationHeaderValue(origin: String = AUTH_ORIGIN, account: YtConnection = connection.value): String? {
        val sapisid = sapisid(account) ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val payload = "$timestamp $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hex"
    }

    suspend fun connect(rawCookieHeader: String, accountName: String, channelHandle: String?, photoUrl: String?) {
        val cookies = parseCookieHeader(rawCookieHeader)
        if (cookies.isEmpty()) return
        val name = accountName.ifBlank { "Google account" }
        // Optimistic in-memory update: DataStore persistence + Flow
        // re-collection is async and loses the race against an immediate
        // verify on slow devices, which then see DISCONNECTED and report
        // "YouTube rejected the session" for a perfectly good session.
        // Matches what saveConnection persists (fresh login resets channel
        // selection), so the collector converges to the same value.
        _connection.value = YtConnection(
            cookies = cookies,
            accountName = name,
            channelHandle = channelHandle,
            photoUrl = photoUrl,
        )
        preferences.saveConnection(cookies, name, channelHandle, photoUrl)
    }

    suspend fun updateAccountIdentity(accountName: String, channelHandle: String?, photoUrl: String?) {
        val current = connection.value
        if (!current.isConnected) return
        // A display-identity refresh must never reset the selected channel.
        preferences.saveConnection(
            current.cookies,
            accountName,
            channelHandle,
            photoUrl,
            onBehalfOfUser = current.onBehalfOfUser,
            authUserIndex = current.authUserIndex,
            pageId = current.pageId,
        )
    }

    /** Clears cookies + identity AND the local→remote mapping table, so a
     *  later reconnect starts clean instead of writing into stale playlists
     *  owned by whoever signed in previously. */
    suspend fun signOut() = preferences.clearConnection()

    /**
     * Attempts to read updated session cookies from Android's system CookieManager.
     * If valid credentials are found and differ from the active connection,
     * updates the connection in preferences automatically.
     * Returns true if fresh cookies were found and saved.
     */
    suspend fun refreshCookiesFromCookieManager(): Boolean {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            val musicCookies = cm.getCookie("https://music.youtube.com").orEmpty()
            val ytCookies = cm.getCookie("https://www.youtube.com").orEmpty()
            val combined = when {
                musicCookies.isBlank() -> ytCookies
                ytCookies.isBlank() -> musicCookies
                else -> "$musicCookies; $ytCookies"
            }
            if (combined.isBlank()) return false
            val cookies = parseCookieHeader(combined)
            val hasSapisid = listOf(COOKIE_SAPISID_PRIMARY, "SAPISID", "APISID").any { cookies.containsKey(it) }
            val hasLogin = cookies.containsKey("LOGIN_INFO")
            if (!hasSapisid || !hasLogin) return false

            val current = connection.value
            if (current.isConnected && current.cookies == cookies) return false

            preferences.saveConnection(
                cookies = cookies,
                accountName = current.accountName.ifBlank { "Google account" },
                channelHandle = current.channelHandle,
                photoUrl = current.photoUrl,
                onBehalfOfUser = current.onBehalfOfUser,
                authUserIndex = current.authUserIndex,
                pageId = current.pageId,
            )
            Log.i(TAG, "Auto-refreshed YouTube Music session cookies from CookieManager")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not auto-refresh cookies from CookieManager", e)
            false
        }
    }


    private fun parseCookieHeader(raw: String): Map<String, String> =
        raw.split(';')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else {
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isBlank() || value.isBlank()) null else name to value
                }
            }
            .toMap()

    private companion object {
        const val TAG = "YtMusicAuthManager"
        const val COOKIE_SAPISID_PRIMARY = "__Secure-3PAPISID"
        const val AUTH_ORIGIN = "https://music.youtube.com"
    }
}
