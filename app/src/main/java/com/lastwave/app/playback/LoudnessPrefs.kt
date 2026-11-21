package com.lastwave.app.playback

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated DataStore file for loudness normalization, separate from the
 * shared app preferences so the feature owns its keys and defaults.
 */
internal val Context.loudnessDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "loudness_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class LoudnessSettings(
    val mode: LoudnessMode = LoudnessMode.OFF,
    val preampDb: Float = 0f,
)

/**
 * Persists loudness-normalization preferences (default OFF: zero behavior
 * change until the user opts in). Exposes the combined [settings] plus the
 * individual [mode] and [preampDb] StateFlows for collectors.
 */
@Singleton
class LoudnessPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
    applicationScope: CoroutineScope,
) {
    private object Keys {
        val MODE = stringPreferencesKey("lw_loudness_mode")
        val PREAMP_DB = floatPreferencesKey("lw_loudness_preamp_db")
    }

    private val data: Flow<Preferences> = context.loudnessDataStore.data
        .catch { error ->
            Log.e(TAG, "Loudness preferences unavailable; using safe defaults", error)
            emit(emptyPreferences())
        }

    val settings: StateFlow<LoudnessSettings> = data
        .map { prefs ->
            LoudnessSettings(
                mode = LoudnessMode.fromId(prefs[Keys.MODE]),
                preampDb = sanitizePreamp(prefs[Keys.PREAMP_DB]),
            )
        }
        .stateIn(applicationScope, SharingStarted.Eagerly, LoudnessSettings())

    val mode: StateFlow<LoudnessMode> = data
        .map { prefs -> LoudnessMode.fromId(prefs[Keys.MODE]) }
        .stateIn(applicationScope, SharingStarted.Eagerly, LoudnessMode.OFF)

    val preampDb: StateFlow<Float> = data
        .map { prefs -> sanitizePreamp(prefs[Keys.PREAMP_DB]) }
        .stateIn(applicationScope, SharingStarted.Eagerly, 0f)

    suspend fun setMode(mode: LoudnessMode) {
        context.loudnessDataStore.edit { it[Keys.MODE] = mode.id }
    }

    suspend fun setPreampDb(preampDb: Float) {
        context.loudnessDataStore.edit { it[Keys.PREAMP_DB] = sanitizePreamp(preampDb) }
    }

    private fun sanitizePreamp(value: Float?): Float =
        if (value != null && value.isFinite()) {
            value.coerceIn(-LOUDNESS_PREAMP_MAX_DB, LOUDNESS_PREAMP_MAX_DB)
        } else {
            0f
        }

    private companion object {
        const val TAG = "LoudnessPrefs"
    }
}
