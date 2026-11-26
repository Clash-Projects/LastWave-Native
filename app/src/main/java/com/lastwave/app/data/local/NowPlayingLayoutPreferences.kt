package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in for the redesigned Now Playing panel. Lives in its own class, not in
 * SettingsPreferences, so the preview never collides with upstream edits there.
 */
@Singleton
class NowPlayingLayoutPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val redesignEnabled: Flow<Boolean> = dataStore.data
        .recoverPreferences("NowPlayingLayoutPreferences")
        .map { it.readSafely(REDESIGN) ?: false }

    suspend fun setRedesignEnabled(enabled: Boolean) {
        dataStore.edit { it[REDESIGN] = enabled }
    }

    private companion object {
        val REDESIGN = booleanPreferencesKey("lw_now_playing_redesign")
    }
}
