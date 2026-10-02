package com.lastwave.app.diagnostics

import android.content.Context

/**
 * Assembles the whole diagnostics subsystem, in the only order that works.
 *
 * Order matters more than it looks:
 *
 * 1. The previous session's [SessionMarker] is read *before* this session
 *    overwrites it, otherwise a clean launch would erase the evidence that the
 *    last one died.
 * 2. [AppLog] installs before anything can report a failure, so the rest of
 *    startup is already covered.
 * 3. [ExitReasonReader] runs after logging exists, because its findings are
 *    themselves crash records.
 *
 * Installed from `Application.attachBaseContext`, which is the earliest point
 * where a Context exists — ContentProviders are created before `onCreate`, and
 * failures in that phase were previously invisible.
 */
object Diagnostics {

    private const val TAG = "Diagnostics"

    @Volatile
    private var watchdog: AnrWatchdog? = null

    fun install(context: Context) {
        runCatching {
            val uncleanSession = SessionMarker.takeUncleanSession(context)
            SessionMarker.markRunning(context)

            AppLog.install(context)
            CrashHandler.install(context)

            AppLog.i(TAG, "Diagnostics starting (pid=${android.os.Process.myPid()})")

            // A native crash, a low-memory kill, or a system-recorded ANR leave
            // no Java stack trace for the crash handler to see. Android 11+
            // records them itself and this surfaces them as crash files.
            runCatching { ExitReasonReader.recordHistoricalExits(context) }

            if (uncleanSession != null) {
                // Not a crash on its own: the marker cannot tell a system kill
                // from the user swiping the app away, and says so.
                CrashRecorder.record(
                    type = CrashType.UNKNOWN_TERMINATION,
                    detail = buildString {
                        appendLine("The previous session ended without a clean-shutdown marker.")
                        appendLine("The system may have killed the process, or the app may have been")
                        appendLine("swiped from recents. Recorded marker: $uncleanSession")
                    },
                )
            }

            startAnrWatchdog()
        }.onFailure {
            android.util.Log.e(TAG, "Diagnostics unavailable; continuing without it", it)
        }
    }

    /**
     * Marks the session as ended deliberately. Called when the last activity
     * finishes for good — not on a configuration change.
     */
    fun markCleanShutdown(context: Context) {
        runCatching { SessionMarker.markClean(context) }
    }

    /** Writes everything queued right now. Called when the app goes to the background. */
    fun flush() {
        runCatching { AppLog.flush() }
    }

    private fun startAnrWatchdog() {
        if (watchdog != null) return
        val started = AnrWatchdog { stalledMillis, mainStack ->
            runCatching {
                AppLog.e(TAG, "Main thread stalled for ${stalledMillis}ms")
                CrashRecorder.record(
                    type = CrashType.ANR,
                    detail = buildString {
                        appendLine("Main thread unresponsive for ${stalledMillis}ms.")
                        appendLine()
                        appendLine("--- main thread stack ---")
                        mainStack.forEach { appendLine("  at $it") }
                    },
                )
            }
        }
        watchdog = started
        started.start()
    }
}