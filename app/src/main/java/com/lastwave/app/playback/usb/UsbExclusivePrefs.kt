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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

private const val USB_EXCLUSIVE_PREFS_NAME = "usb_exclusive_prefs"

internal val Context.usbExclusiveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = USB_EXCLUSIVE_PREFS_NAME,
)

/**
 * Standalone toggle for USB exclusive (bit-perfect) output.
 *
 * Uses its own DataStore file so the flag stays independent of the main
 * settings store. Disabled by default: every read fails closed to `false`,
 * so playback behavior is unchanged until the toggle is explicitly enabled.
 */
object UsbExclusivePrefs {

    private val KEY_ENABLED = booleanPreferencesKey("usb_exclusive_enabled")

    fun enabledFlow(context: Context): Flow<Boolean> =
        context.applicationContext.usbExclusiveDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[KEY_ENABLED] == true }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.usbExclusiveDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
        }
    }

    /**
     * Synchronous-friendly read for threads that cannot collect a Flow
     * (e.g. the renderer factory thread). Returns `false` on timeout or error.
     */
    suspend fun isEnabledNow(context: Context, timeoutMs: Long = 500L): Boolean =
        withTimeoutOrNull(timeoutMs) { enabledFlow(context).first() } ?: false
}
