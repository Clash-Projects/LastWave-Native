package com.lastwave.app.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

val LocalIsTv = compositionLocalOf { false }

@Singleton
class TvDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isTv: Boolean by lazy {
        detectIsTv(context)
    }

    companion object {
        fun detectIsTv(context: Context): Boolean {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true
            }

            val packageManager = context.packageManager
            return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                @Suppress("DEPRECATION")
                packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        }
    }
}

@Composable
fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        TvDetector.detectIsTv(context)
    }
}
