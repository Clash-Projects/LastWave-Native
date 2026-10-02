package com.lastwave.app.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.lastwave.app.diagnostics.core.LogLevel

/**
 * Decides which records are worth writing to disk.
 *
 * Errors and warnings are always persisted — they are the reason the system
 * exists. Debug and informational output is only persisted in debuggable builds,
 * or when the user explicitly turns on verbose logging so they can reproduce a
 * problem and hand over the file.
 *
 * Reads are cheap and lock-free: the two flags are volatile and [diskMinimum]
 * is a comparison, safe to call from an audio callback.
 */
object LogConfig {

    private const val PREFS_NAME = "diagnostics"
    private const val KEY_VERBOSE = "verbose_logging"

    @Volatile
    private var debuggable: Boolean = false

    @Volatile
    private var verbose: Boolean = false

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Called once from the Application. Safe to skip; logging then stays minimal. */
    fun install(context: Context) {
        runCatching {
            debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val appContext = context.applicationContext ?: context
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            verbose = prefs?.getBoolean(KEY_VERBOSE, false) ?: false
        }
    }

    fun isVerboseEnabled(): Boolean = verbose

    fun isDebuggable(): Boolean = debuggable

    /** Persists the user's verbose choice so it survives a restart. */
    fun setVerboseEnabled(enabled: Boolean) {
        verbose = enabled
        prefs?.edit()?.putBoolean(KEY_VERBOSE, enabled)?.apply()
    }

    /** The least severe level that reaches disk. */
    fun diskMinimum(): LogLevel = when {
        verbose -> LogLevel.VERBOSE
        debuggable -> LogLevel.DEBUG
        else -> LogLevel.WARN
    }

    /** True when [level] is severe enough to be written to disk. */
    fun shouldPersist(level: LogLevel): Boolean = level.isAtLeast(diskMinimum())
}