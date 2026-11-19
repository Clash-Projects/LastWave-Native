package com.lastwave.app.ui.tv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The TV app's own preferences, in the manner of BitChord's TvSettings — a
 * tiny process-wide object backed by SharedPreferences, kept completely
 * separate from the phone Settings store. Loaded by [TvHomeActivity] before
 * the first composition reads these flows.
 */
object TvSettings {
    private var prefs: SharedPreferences? = null

    /** First-run setup wizard finished (skipped counts as finished). */
    val setupCompleted = MutableStateFlow(false)

    /**
     * Virtual mouse pointer inside the sign-in WebViews. On = the D-pad moves
     * the pointer dot and OK clicks/drags. Off = the WebView owns the remote
     * natively (arrows traverse links, OK clicks, Page keys scroll).
     */
    val virtualMouse = MutableStateFlow(true)

    fun init(context: Context) {
        val p = context.getSharedPreferences("lastwave_tv", Context.MODE_PRIVATE)
        prefs = p
        setupCompleted.value = p.getBoolean(KEY_SETUP_COMPLETED, false)
        virtualMouse.value = p.getBoolean(KEY_VIRTUAL_MOUSE, true)
    }

    fun setSetupCompleted(value: Boolean) {
        setupCompleted.value = value
        prefs?.edit()?.putBoolean(KEY_SETUP_COMPLETED, value)?.apply()
    }

    fun setVirtualMouse(value: Boolean) {
        virtualMouse.value = value
        prefs?.edit()?.putBoolean(KEY_VIRTUAL_MOUSE, value)?.apply()
    }

    private const val KEY_SETUP_COMPLETED = "tv_setup_completed"
    private const val KEY_VIRTUAL_MOUSE = "tv_virtual_mouse"
}
