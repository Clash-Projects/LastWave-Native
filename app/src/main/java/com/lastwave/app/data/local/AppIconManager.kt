package com.lastwave.app.data.local

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppIconManager {
    private const val TAG = "AppIconManager"

    fun applyIcon(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        val pkg = context.packageName

        // First, disable all aliases except the target
        AppIcon.entries.forEach { entry ->
            if (entry == icon) return@forEach  // enable target last
            val comp = ComponentName(pkg, entry.componentName)
            try {
                val currentState = pm.getComponentEnabledSetting(comp)
                if (currentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d(TAG, "Disabled ${entry.componentName}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable ${entry.componentName}: ${e.message}")
            }
        }

        // Enable the target alias last — this triggers the PACKAGE_CHANGED
        // broadcast that forces launchers (including Moto, Pixel, stock AOSP)
        // to refresh the cached icon on the home screen.
        val targetComp = ComponentName(pkg, icon.componentName)
        try {
            pm.setComponentEnabledSetting(
                targetComp,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "Enabled ${icon.componentName}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable ${icon.componentName}: ${e.message}")
        }

        // Some launchers (Moto, certain AOSP forks) cache the icon aggressively
        // and only refresh on an explicit MY_PACKAGE_REPLACED-style event.
        // Toggling the target alias off then back on forces the system to emit
        // a stronger PACKAGE_CHANGED that clears the launcher icon cache.
        try {
            pm.setComponentEnabledSetting(
                targetComp,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                targetComp,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "Toggled ${icon.componentName} to force launcher cache invalidation")
        } catch (e: Exception) {
            Log.w(TAG, "Force-toggle failed for ${icon.componentName}: ${e.message}")
        }
    }
}

