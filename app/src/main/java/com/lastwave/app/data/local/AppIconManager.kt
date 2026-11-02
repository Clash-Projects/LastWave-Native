package com.lastwave.app.data.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

object AppIconManager {
    private const val TAG = "AppIconManager"
    private val lock = Any()

    private fun getComponent(context: Context, name: String): ComponentName {
        val pkg = context.packageName
        val cls = if (name.startsWith(".")) "$pkg$name" else if (!name.contains(".")) "$pkg.$name" else name
        return ComponentName(pkg, cls)
    }

    fun isIconActive(context: Context, icon: AppIcon): Boolean {
        return try {
            val pm = context.packageManager
            val targetComp = getComponent(context, icon.componentName)
            val state = pm.getComponentEnabledSetting(targetComp)
            if (icon == AppIcon.DEFAULT) {
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
        } catch (_: Exception) {
            false
        }
    }

    fun applyIcon(context: Context, icon: AppIcon) {
        synchronized(lock) {
            if (isIconActive(context, icon)) {
                Log.d(TAG, "Icon ${icon.name} is already active, skipping apply")
                return
            }

            val pm = context.packageManager
            val targetComp = getComponent(context, icon.componentName)
            val defaultAliasComp = getComponent(context, "com.lastwave.app.MainActivityDefault")
            val legacyComp = getComponent(context, "com.lastwave.app.MainActivity")

            // 1. Enable the target alias FIRST so there is never a moment
            // where no launcher activity is enabled
            try {
                pm.setComponentEnabledSetting(
                    targetComp,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                Log.d(TAG, "Enabled target alias ${icon.componentName}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable target alias ${icon.componentName}: ${e.message}")
            }

            // 2. Disable all other aliases safely
            AppIcon.entries.forEach { entry ->
                if (entry == icon) return@forEach
                val comp = getComponent(context, entry.componentName)
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

            // Always ensure MainActivityDefault is disabled to prevent duplicate launcher icons
            try {
                val defaultState = pm.getComponentEnabledSetting(defaultAliasComp)
                if (defaultState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        defaultAliasComp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable MainActivityDefault alias: ${e.message}")
            }

            if (icon != AppIcon.DEFAULT) {
                try {
                    val legacyState = pm.getComponentEnabledSetting(legacyComp)
                    if (legacyState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(
                            legacyComp,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to disable legacy MainActivity alias: ${e.message}")
                }
            }

            // 3. Immediately restart the task with the newly enabled component as the root,
            // preventing Android ActivityManager from retaining a disabled component as baseIntent
            // which causes the system to redirect to "App Info" on resume or app launch!
            try {
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = targetComp
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to relaunch task with target component: ${e.message}")
            }
        }
    }
}
