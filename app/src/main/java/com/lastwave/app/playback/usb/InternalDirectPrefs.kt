package com.lastwave.app.playback.usb

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.internalDirectDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "internal_direct_prefs",
)

/**
 * Toggles for the "mixer bypass without a USB DAC" fork feature.
 *
 * Own DataStore file, like [UsbExclusivePrefs], so upstream settings stay
 * untouched. Both default to false and fail closed on read errors.
 */
object InternalDirectPrefs {

    private val KEY_ENABLED = booleanPreferencesKey("internal_direct_enabled")
    private val KEY_FORCE_SOFTWARE_VOLUME = booleanPreferencesKey("force_software_volume")

    fun enabledFlow(context: Context): Flow<Boolean> =
        context.applicationContext.internalDirectDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[KEY_ENABLED] == true }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.internalDirectDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    /** True = never use the DAC's hardware (Feature Unit) volume in USB exclusive mode. */
    fun forceSoftwareVolumeFlow(context: Context): Flow<Boolean> =
        context.applicationContext.internalDirectDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[KEY_FORCE_SOFTWARE_VOLUME] == true }

    suspend fun setForceSoftwareVolume(context: Context, enabled: Boolean) {
        context.applicationContext.internalDirectDataStore.edit { it[KEY_FORCE_SOFTWARE_VOLUME] = enabled }
    }
}
