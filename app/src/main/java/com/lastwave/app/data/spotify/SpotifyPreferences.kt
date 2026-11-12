package com.lastwave.app.data.spotify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val spdc: Flow<String?> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(SP_DC_KEY)?.takeIf { s -> s.isNotBlank() } }

    val personalToken: Flow<String?> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(PERSONAL_TOKEN_KEY)?.takeIf { s -> s.isNotBlank() } }

    val personalTokenExpires: Flow<Long> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(PERSONAL_TOKEN_EXPIRES_KEY) ?: 0L }

    val clientToken: Flow<String?> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(CLIENT_TOKEN_KEY)?.takeIf { s -> s.isNotBlank() } }

    val clientTokenExpires: Flow<Long> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(CLIENT_TOKEN_EXPIRES_KEY) ?: 0L }

    val canvasEnabled: Flow<Boolean> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(CANVAS_ENABLED_KEY) ?: true }

    /**
     * Whether Spotify is asked before the other providers.
     *
     * Off by default: the other providers answer without an account and cover most of what
     * Spotify would have answered, and Spotify is the one that needs a session. On, Spotify is
     * asked first so that a track with a canvas on Spotify gets Spotify's clip.
     */
    val preferSpotifyCanvas: Flow<Boolean> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(PREFER_SPOTIFY_CANVAS_KEY) ?: true }

    val isConnected: Flow<Boolean> = spdc.map { !it.isNullOrBlank() }
    /** The per-install device id the client-token profile presents. */
    val deviceId: Flow<String> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(DEVICE_ID_KEY).orEmpty() }

    suspend fun getSpdc(): String? = spdc.first()
    suspend fun getPersonalToken(): String? = personalToken.first()
    suspend fun getPersonalTokenExpires(): Long = personalTokenExpires.first()
    suspend fun getClientToken(): String? = clientToken.first()
    suspend fun getClientTokenExpires(): Long = clientTokenExpires.first()
    suspend fun isCanvasEnabled(): Boolean = canvasEnabled.first()
    suspend fun isPreferSpotifyCanvas(): Boolean = preferSpotifyCanvas.first()

    /**
     * The device id this install presents, generated once and kept.
     *
     * The client-token endpoint is told which device is asking. Two installs sharing one
     * id are two browsers from one machine, so the id is generated per install instead.
     */
    suspend fun getOrCreateDeviceId(): String {
        deviceId.first().takeIf { it.isNotBlank() }?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            // Another writer may have won the race; keep whichever id is stored.
            val stored = prefs[DEVICE_ID_KEY]
            if (stored.isNullOrBlank()) prefs[DEVICE_ID_KEY] = generated
        }
        return deviceId.first().ifBlank { generated }
    }

    suspend fun saveSpdc(spdcValue: String) {
        val clean = spdcValue.trim().let {
            if (it.startsWith("sp_dc=")) it.substringAfter("sp_dc=").substringBefore(";") else it
        }
        dataStore.edit { prefs ->
            prefs[SP_DC_KEY] = clean
            // Invalidate personal token so fresh one is generated with this sp_dc
            prefs.remove(PERSONAL_TOKEN_KEY)
            prefs.remove(PERSONAL_TOKEN_EXPIRES_KEY)
        }
    }

    suspend fun savePersonalToken(token: String, expiresAtMs: Long) {
        dataStore.edit { prefs ->
            prefs[PERSONAL_TOKEN_KEY] = token
            prefs[PERSONAL_TOKEN_EXPIRES_KEY] = expiresAtMs
        }
    }

    suspend fun saveClientToken(token: String, expiresAtMs: Long) {
        dataStore.edit { prefs ->
            prefs[CLIENT_TOKEN_KEY] = token
            prefs[CLIENT_TOKEN_EXPIRES_KEY] = expiresAtMs
        }
    }

    suspend fun setCanvasEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[CANVAS_ENABLED_KEY] = enabled
        }
    }

    suspend fun setPreferSpotifyCanvas(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PREFER_SPOTIFY_CANVAS_KEY] = enabled
        }
    }


    suspend fun clearConnection() {
        dataStore.edit { prefs ->
            prefs.remove(SP_DC_KEY)
            prefs.remove(PERSONAL_TOKEN_KEY)
            prefs.remove(PERSONAL_TOKEN_EXPIRES_KEY)
            prefs.remove(CLIENT_TOKEN_KEY)
            prefs.remove(CLIENT_TOKEN_EXPIRES_KEY)
        }
    }

    private companion object {
        val SP_DC_KEY = stringPreferencesKey("spotify_sp_dc")
        val PERSONAL_TOKEN_KEY = stringPreferencesKey("spotify_personal_token")
        val PERSONAL_TOKEN_EXPIRES_KEY = longPreferencesKey("spotify_personal_token_expires")
        val CLIENT_TOKEN_KEY = stringPreferencesKey("spotify_client_token")
        val CLIENT_TOKEN_EXPIRES_KEY = longPreferencesKey("spotify_client_token_expires")
        val CANVAS_ENABLED_KEY = booleanPreferencesKey("spotify_canvas_enabled")
        val PREFER_SPOTIFY_CANVAS_KEY = booleanPreferencesKey("spotify_prefer_canvas")
        val DEVICE_ID_KEY = stringPreferencesKey("spotify_device_id")
    }
}
