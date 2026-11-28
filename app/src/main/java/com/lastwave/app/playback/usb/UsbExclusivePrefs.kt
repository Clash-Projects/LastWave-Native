package com.lastwave.app.playback.usb

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

private const val USB_EXCLUSIVE_PREFS_NAME = "usb_exclusive_prefs"

internal val Context.usbExclusiveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = USB_EXCLUSIVE_PREFS_NAME,
)

/**
 * Preferences for USB exclusive (bit-perfect) output.
 *
 * The feature is disabled by default. Buffer duration is stored with the
 * enable flag so the existing USB preference backup includes it.
 */
object UsbExclusivePrefs {
    const val DEFAULT_BUFFER_MS = 80
    const val MIN_BUFFER_MS = 40
    const val MAX_BUFFER_MS = 480
    const val BUFFER_STEP_MS = 40

    private val KEY_ENABLED = booleanPreferencesKey("usb_exclusive_enabled")

    private val KEY_BUFFER_MS = intPreferencesKey("iso_buffer_ms")

    internal fun normalizeBufferMs(value: Int): Int =
        (value.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS) + BUFFER_STEP_MS / 2) /
            BUFFER_STEP_MS * BUFFER_STEP_MS

    fun bufferDurationFlow(context: Context): Flow<Int> =
        context.applicationContext.usbExclusiveDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { normalizeBufferMs(it[KEY_BUFFER_MS] ?: DEFAULT_BUFFER_MS) }
            .distinctUntilChanged()

    suspend fun setBufferDuration(context: Context, bufferMs: Int) {
        context.applicationContext.usbExclusiveDataStore.edit {
            it[KEY_BUFFER_MS] = normalizeBufferMs(bufferMs)
        }
    }

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
