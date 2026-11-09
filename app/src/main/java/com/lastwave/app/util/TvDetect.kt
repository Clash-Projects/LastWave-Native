package com.lastwave.app.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * True on Android TV / Google TV / Fire TV builds, detected once per process
 * from either the UI mode or the leanback system feature (Chromecast-with-
 * Google-TV reports LEANBACK only while some boxes advertise the UI mode).
 */
object TvDetect {

    @Volatile
    private var cached: Boolean? = null

    fun isTvMode(context: Context): Boolean {
        cached?.let { return it }
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        cached = isTv
        return isTv
    }
}
