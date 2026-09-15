package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bring-your-own-key: there is no shared Last.fm key anywhere in the app.
 * [apiKey]/[apiSecret] are blank until the user pastes their own key
 * (created at last.fm/api/account/create) in Settings → Integrations.
 * Every Last.fm call site treats a blank key as "no key" and skips itself.
 */
data class SessionData(
    val apiKey: String = "",
    val apiSecret: String = "",
    val sessionKey: String = "",
    val username: String = "",
    val isLoaded: Boolean = true,
) {
    val isAuthenticated: Boolean get() = isLoaded && username.isNotBlank()
    val hasApiKey: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
}

@Singleton
class SessionPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val externalScope: CoroutineScope,
) {
    private object Keys {
        val API_KEY = stringPreferencesKey("lw_apikey")
        val API_SECRET = stringPreferencesKey("lw_apisecret")
        val SESSION_KEY = stringPreferencesKey("lw_sessionkey")
        val USERNAME = stringPreferencesKey("lw_username")
        val GUEST_MODE = booleanPreferencesKey("lw_guest_mode")
    }

    val session: StateFlow<SessionData> = dataStore.data
        .recoverPreferences("SessionPreferences")
        .map { p ->
            val storedKey = p.readSafely(Keys.API_KEY)
            val storedSecret = p.readSafely(Keys.API_SECRET)
            SessionData(
                apiKey = storedKey ?: "",
                apiSecret = storedSecret ?: "",
                sessionKey = p.readSafely(Keys.SESSION_KEY) ?: "",
                username = p.readSafely(Keys.USERNAME) ?: "",
                isLoaded = true,
            )
        }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = SessionData(
            apiKey = "",
            apiSecret = "",
            sessionKey = "",
            username = "",
            isLoaded = false,
        )
    )

    val currentSession: SessionData get() = session.value

    /**
     * Guest mode is the account-free onboarding path: the user skips both
     * YouTube Music and Last.fm and enters MainShell immediately. It is
     * persisted in the same DataStore so a previously-selected guest session
     * skips Login on the next cold start (see NavGraph LaunchGate). Any real
     * sign-in (YouTube or Last.fm) clears it; see [saveSession]/[setSignedIn].
     */
    val guestMode: StateFlow<Boolean> = dataStore.data
        .recoverPreferences("SessionPreferences.guestMode")
        .map { p -> p.readSafely(Keys.GUEST_MODE) ?: false }
        .stateIn(
            scope = externalScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    val isGuestMode: Boolean get() = guestMode.value

    /** Enter account-free guest mode, clearing any stale Last.fm identity. */
    suspend fun enterGuestMode() {
        dataStore.edit {
            it.remove(Keys.SESSION_KEY)
            it.remove(Keys.USERNAME)
            it[Keys.GUEST_MODE] = true
        }
    }

    suspend fun setGuestMode(enabled: Boolean) {
        dataStore.edit {
            it[Keys.GUEST_MODE] = enabled
            if (enabled) {
                it.remove(Keys.SESSION_KEY)
                it.remove(Keys.USERNAME)
            }
        }
    }

    suspend fun exitGuestMode() {
        dataStore.edit { it[Keys.GUEST_MODE] = false }
    }

    suspend fun saveSession(username: String, sessionKey: String = "", apiKey: String = "", apiSecret: String = "") {
        dataStore.edit {
            it[Keys.USERNAME] = username.trim()
            it[Keys.SESSION_KEY] = sessionKey.trim()
            it[Keys.API_KEY] = apiKey.trim()
            it[Keys.API_SECRET] = apiSecret.trim()
            it[Keys.GUEST_MODE] = false
        }
    }

    suspend fun setApiCredentials(apiKey: String, apiSecret: String) {
        dataStore.edit {
            it[Keys.API_KEY] = apiKey
            it[Keys.API_SECRET] = apiSecret
        }
    }

    /** Direct sign-in — no token, no session exchange: the verified
     *  username is stored straight away as the signed-in identity. */
    suspend fun setSignedIn(username: String) {
        dataStore.edit {
            it[Keys.USERNAME] = username
            it[Keys.GUEST_MODE] = false
        }
    }

    /** Stores a real Last.fm session key (`sk`) obtained via
     *  auth.getMobileSession — the one signed call that needs a plaintext
     *  password, kept as a separate opt-in step from normal sign-in (see
     *  AuthRepository.obtainSessionKey) rather than required for everyone,
     *  since it's only needed to unlock scrobbling and track.scrobble.delete. */
    suspend fun setSessionKey(key: String) {
        dataStore.edit { it[Keys.SESSION_KEY] = key }
    }

    suspend fun signOut() {
        dataStore.edit {
            it.remove(Keys.SESSION_KEY)
            it.remove(Keys.USERNAME)
            it[Keys.GUEST_MODE] = false
            // The user's own API key/secret are intentionally kept, so a
            // disconnect never forces re-pasting the key to reconnect.
        }
    }

    /** Settings' "Log Out" — matches settings.js's logoutApiCredentials():
     *  clears username + API key/secret. Playlists and cached data are kept. */
    suspend fun logOutApiCredentials() {
        dataStore.edit {
            it.remove(Keys.USERNAME)
            it.remove(Keys.API_KEY)
            it.remove(Keys.API_SECRET)
            it[Keys.GUEST_MODE] = false
        }
    }

    /** Settings' "Clear Session" — matches settings.js's clearAllData():
     *  a full wipe. Since ThemePreferences shares this same DataStore
     *  instance, this also resets theme/accent settings back to defaults,
     *  exactly like the original's localStorage.clear(). */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
