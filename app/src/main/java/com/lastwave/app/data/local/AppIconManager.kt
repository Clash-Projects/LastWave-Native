package com.lastwave.app.data.local

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppIconManager {
    private const val TAG = "AppIconManager"
    private val lock = Any()

    fun applyIcon(context: Context, icon: AppIcon) {
        synchronized(lock) {
            val pm = context.packageManager
            val pkg = context.packageName
            val targetComp = ComponentName(pkg, icon.componentName)

            // 1. Enable the target alias FIRST so there is never a moment
            // where no launcher activity is enabled (which triggers Android
            // to open the system App Info screen on tap).
            try {
                val targetState = pm.getComponentEnabledSetting(targetComp)
                if (targetState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    pm.setComponentEnabledSetting(
                        targetComp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                    Log.d(TAG, "Enabled target alias ${icon.componentName}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable target alias ${icon.componentName}: ${e.message}")
            }

            // 2. Disable all other aliases safely
            AppIcon.entries.forEach { entry ->
                if (entry == icon) return@forEach
                val comp = ComponentName(pkg, entry.componentName)
                try {
                    val currentState = pm.getComponentEnabledSetting(comp)
                    if (currentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(
                            comp,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                        Log.d(TAG, "Disabled alias ${entry.componentName}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to disable alias ${entry.componentName}: ${e.message}")
                }
            }
        }
    }
}

