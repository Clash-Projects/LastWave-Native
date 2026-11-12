package com.lastwave.app.data.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Switches the launcher icon by enabling exactly one `<activity-alias>`.
 *
 * Duplicate icons appear whenever more than one launcher alias is enabled at
 * once. That happened after updates: older builds enabled `.MainActivityDefault`
 * explicitly, the manifest enables `.MainActivity` by default, and enabled
 * states survive an update, so both showed. The old apply path also returned
 * early when the chosen alias was already enabled, so it never cleaned up the
 * extra one. Every path now enforces "the chosen alias and nothing else".
 */
object AppIconManager {
    private const val TAG = "AppIconManager"
    private val lock = Any()

    /** Legacy default alias from older builds; kept in the manifest, always disabled. */
    private const val LEGACY_DEFAULT_ALIAS = "com.lastwave.app.MainActivityDefault"
    /** The only alias the manifest enables by default (android:enabled="true"). */
    private const val MANIFEST_ENABLED_ALIAS = "com.lastwave.app.MainActivity"

    private fun getComponent(context: Context, name: String): ComponentName {
        val pkg = context.packageName
        val cls = if (name.startsWith(".")) "$pkg$name" else if (!name.contains(".")) "$pkg.$name" else name
        return ComponentName(pkg, cls)
    }

    private fun allLauncherAliases(): List<String> =
        (AppIcon.entries.map { it.componentName } + LEGACY_DEFAULT_ALIAS).distinct()

    /** Whether the alias currently shows in the launcher (explicit state, else manifest default). */
    private fun isEffectivelyEnabled(pm: PackageManager, comp: ComponentName, name: String): Boolean =
        when (pm.getComponentEnabledSetting(comp)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == MANIFEST_ENABLED_ALIAS
            else -> false
        }

    fun isIconActive(context: Context, icon: AppIcon): Boolean = try {
        val pm = context.packageManager
        isEffectivelyEnabled(pm, getComponent(context, icon.componentName), icon.componentName)
    } catch (_: Exception) {
        false
    }

    /**
     * Makes [icon]'s alias the only enabled launcher entry.
     * @return true when any component state had to change.
     */
    private fun enforceSingle(context: Context, icon: AppIcon): Boolean {
        val pm = context.packageManager
        val target = icon.componentName
        val enabledNow = allLauncherAliases().filter { name ->
            runCatching { isEffectivelyEnabled(pm, getComponent(context, name), name) }.getOrDefault(false)
        }
        if (enabledNow == listOf(target)) return false

        // 1. Enable the target first so there is never a moment with no launcher entry.
        try {
            pm.setComponentEnabledSetting(
                getComponent(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable alias $target: ${e.message}")
            return false // never disable the others if the target could not be enabled
        }
        // 2. Explicitly disable every other alias, including the manifest-enabled one.
        allLauncherAliases().filter { it != target }.forEach { name ->
            val comp = getComponent(context, name)
            try {
                if (pm.getComponentEnabledSetting(comp) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable alias $name: ${e.message}")
            }
        }
        return true
    }

    /**
     * Heals duplicate (or missing) launcher icons without relaunching anything.
     * Called on every app start so a state left by an update is fixed at once.
     */
    fun reconcile(context: Context, icon: AppIcon) {
        synchronized(lock) {
            if (enforceSingle(context, icon)) Log.d(TAG, "Reconciled launcher aliases to ${icon.name}")
        }
    }

    fun applyIcon(context: Context, icon: AppIcon) {
        synchronized(lock) {
            if (!enforceSingle(context, icon)) {
                Log.d(TAG, "Icon ${icon.name} is already the only launcher alias")
                return
            }

            // Restart the task with the newly enabled component as its root, so
            // ActivityManager never keeps a disabled component as baseIntent
            // (which makes the system open "App Info" on resume or launch).
            try {
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = getComponent(context, icon.componentName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to relaunch task with target component: ${e.message}")
            }
        }
    }
}
