package com.lastwave.app.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Reports process deaths that leave no Java stack trace behind.
 *
 * A native crash (a segfault in the render thread or a native audio library),
 * a low-memory kill, and a system-recorded ANR are all invisible to
 * `UncaughtExceptionHandler` — there is no exception to catch. Android 11
 * started recording them itself, and [ActivityManager.getHistoricalProcessExitReasons]
 * exposes the list.
 *
 * This is what makes the Android 16 RenderThread crash class investigable: the
 * process simply vanishes, and without this the only trace is a user saying
 * "it closed by itself".
 *
 * Each exit is reported once, guarded by the highest timestamp already handled,
 * however many times the app is restarted afterwards.
 */
object ExitReasonReader {

    private const val PREFS_NAME = "diagnostics"
    private const val KEY_LAST_EXIT_MILLIS = "last_exit_timestamp"
    private const val MAX_HISTORY = 10

    /** Records any abnormal exit since the last check. Safe to call on every launch. */
    fun recordHistoricalExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val handledUpTo = prefs.getLong(KEY_LAST_EXIT_MILLIS, 0L)

        val history = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_HISTORY)
        }.getOrNull() ?: return

        val newest = history.maxOfOrNull { it.timestamp } ?: 0L

        history.asSequence()
            .filter { it.timestamp > handledUpTo }
            .filter { typeFor(it.reason) != null }
            .sortedBy { it.timestamp }
            .forEach { exit ->
                CrashRecorder.record(
                    type = typeFor(exit.reason)!!,
                    detail = describe(exit),
                )
            }

        if (newest > handledUpTo) {
            prefs.edit().putLong(KEY_LAST_EXIT_MILLIS, newest).apply()
        }
    }

    /** Maps an exit reason to a [CrashType], or null when it was a normal exit. */
    private fun typeFor(reason: Int): CrashType? = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashType.NATIVE_CRASH
        ApplicationExitInfo.REASON_LOW_MEMORY -> CrashType.LOW_MEMORY
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> CrashType.EXCESSIVE_RESOURCE_USE
        ApplicationExitInfo.REASON_ANR -> CrashType.ANR
        // A REASON_CRASH should have produced a Java stack trace of its own; if
        // one is missing, recording the system description is better than silence.
        ApplicationExitInfo.REASON_CRASH -> CrashType.UNCAUGHT_EXCEPTION
        else -> null
    }

    private fun describe(exit: ApplicationExitInfo): String = buildString {
        appendLine("Reason      : ${exit.reason} (${reasonName(exit.reason)})")
        appendLine("Timestamp   : ${exit.timestamp}")
        appendLine("PSS         : ${exit.pss / (1024 * 1024)}MB")
        appendLine("RSS         : ${exit.rss / (1024 * 1024)}MB")
        exit.description?.takeIf { it.isNotBlank() }?.let { appendLine("System says : $it") }
        exit.importance?.let { appendLine("Importance  : $it") }
    }.trimEnd()

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_FREEZER -> "frozen"
        else -> "other"
    }
}