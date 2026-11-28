package com.lastwave.app.playback.usb

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal val Context.internalDirectDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "internal_direct_prefs",
)

/**
 * Toggles for the "mixer bypass without a USB DAC" fork feature.
 *
 * Own DataStore file, like [UsbExclusivePrefs], so upstream settings stay
 * untouched. Both default to false and fail closed on read errors.
 */
enum class UsbVolumeMode(val id: String, val title: String, val subtitle: String) {
    AUTO(
        id = "auto",
        title = "Hardware + Software Fallback",
        subtitle = "Uses DAC hardware volume when supported, software otherwise",
    ),
    SOFTWARE(
        id = "software",
        title = "Software Volume",
        subtitle = "PCM scaled by the app • DAC hardware level parked at 0 dB",
    ),
    FIXED(
        id = "fixed",
        title = "Fixed 100% (Bit-Perfect)",
        subtitle = "Bypass all app volume scaling • Use your DAC's physical volume buttons",
    ),
    ;

    companion object {
        fun fromId(id: String?): UsbVolumeMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

object InternalDirectPrefs {

    private val KEY_ENABLED = booleanPreferencesKey("internal_direct_enabled")
    private val KEY_FORCE_SOFTWARE_VOLUME = booleanPreferencesKey("force_software_volume")
    private val KEY_USB_VOLUME_MODE = stringPreferencesKey("usb_volume_mode")

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
            .map { prefs ->
                val raw = prefs[KEY_USB_VOLUME_MODE]
                if (raw != null) raw == UsbVolumeMode.SOFTWARE.id else prefs[KEY_FORCE_SOFTWARE_VOLUME] == true
            }

    suspend fun setForceSoftwareVolume(context: Context, enabled: Boolean) {
        setUsbVolumeMode(context, if (enabled) UsbVolumeMode.SOFTWARE else UsbVolumeMode.AUTO)
    }

    fun usbVolumeModeFlow(context: Context): Flow<UsbVolumeMode> =
        context.applicationContext.internalDirectDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs ->
                val raw = prefs[KEY_USB_VOLUME_MODE]
                when {
                    raw != null -> UsbVolumeMode.fromId(raw)
                    prefs[KEY_FORCE_SOFTWARE_VOLUME] == true -> UsbVolumeMode.SOFTWARE
                    else -> UsbVolumeMode.AUTO
                }
            }

    suspend fun setUsbVolumeMode(context: Context, mode: UsbVolumeMode) {
        context.applicationContext.internalDirectDataStore.edit {
            it[KEY_USB_VOLUME_MODE] = mode.id
            it[KEY_FORCE_SOFTWARE_VOLUME] = (mode == UsbVolumeMode.SOFTWARE)
        }
    }
}
