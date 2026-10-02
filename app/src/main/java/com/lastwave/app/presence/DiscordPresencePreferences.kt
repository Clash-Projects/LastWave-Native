package com.lastwave.app.presence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * "Show what you're playing on your Discord profile."
 *
 * Default ON, like the desktop client: presence is the feature people install a
 * music app's Discord integration for, and a missing key reads as `true` so
 * existing users start seeing their card without touching a switch. An
 * explicit OFF is stored durably and is never overwritten by anything else —
 * only [setEnabled] writes this key.
 *
 * The key name matches the desktop client's pref (`lw_discord_rich_presence`) so
 * the same human decision has the same identity in both apps.
 */
@Singleton
class DiscordPresencePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("lw_discord_rich_presence")
    }

    val enabled: Flow<Boolean> = dataStore.data
        .recoverPreferences("DiscordPresencePreferences")
        .map { prefs -> prefs.readSafely(Keys.ENABLED) ?: true }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }
}