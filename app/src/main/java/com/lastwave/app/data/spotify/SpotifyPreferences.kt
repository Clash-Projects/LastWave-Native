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

    val syncLikedSongsEnabled: Flow<Boolean> = dataStore.data
        .recoverPreferences("SpotifyPreferences")
        .map { it.readSafely(SYNC_LIKED_SONGS_KEY) ?: true }

    val isConnected: Flow<Boolean> = spdc.map { !it.isNullOrBlank() }

    suspend fun getSpdc(): String? = spdc.first()
    suspend fun getPersonalToken(): String? = personalToken.first()
    suspend fun getPersonalTokenExpires(): Long = personalTokenExpires.first()
    suspend fun getClientToken(): String? = clientToken.first()
    suspend fun getClientTokenExpires(): Long = clientTokenExpires.first()
    suspend fun isCanvasEnabled(): Boolean = canvasEnabled.first()
    suspend fun isSyncLikedSongsEnabled(): Boolean = syncLikedSongsEnabled.first()

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

    suspend fun setSyncLikedSongsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SYNC_LIKED_SONGS_KEY] = enabled
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
        val SYNC_LIKED_SONGS_KEY = booleanPreferencesKey("spotify_sync_liked_songs")
    }
}
