package com.lastwave.app.diagnostics

import android.content.Context
import android.util.Log

/**
 * Replaces [CrashGuard] as the process's last line of defence.
 *
 * The handler does exactly three things, in this order:
 *
 * 1. flushes the queued log so the lines leading up to the crash are on disk
 *    rather than sitting in a write buffer,
 * 2. writes a self-contained crash file including that lead-up,
 * 3. hands control to whatever handler was already installed — typically the
 *    system's — and then terminates.
 *
 * Every step is guarded: a failure to record a crash must never replace the
 * crash the user actually hit, nor prevent the process from dying.
 */
object CrashHandler {

    private const val TAG = "CrashHandler"

    fun install(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                AppLog.flush()
                CrashRecorder.record(CrashType.UNCAUGHT_EXCEPTION, thread, error)
            }.onFailure { failure ->
                Log.e(TAG, "Could not record the crash", failure)
            }

            Log.e(TAG, "Fatal exception on ${thread.name}", error)

            try {
                previousHandler?.uncaughtException(thread, error)
            } finally {
                hardExit()
            }
        }
    }

    /**
     * Terminates the broken process. Both calls are needed: `exit` runs shutdown
     * hooks and would return control to a process whose state is already
     * inconsistent, so the kill is unconditional.
     */
    private fun hardExit() {
        android.os.Process.killProcess(android.os.Process.myPid())
        Runtime.getRuntime().exit(10)
    }
}