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

        AppIcon.entries.forEach { entry ->
            val comp = ComponentName(pkg, entry.componentName)
            val targetState = if (entry == icon) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                val currentState = pm.getComponentEnabledSetting(comp)
                if (currentState != targetState) {
                    pm.setComponentEnabledSetting(
                        comp,
                        targetState,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d(TAG, "Updated ${entry.componentName} to state $targetState")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update component state for ${entry.componentName}: ${e.message}")
            }
        }
    }
}
