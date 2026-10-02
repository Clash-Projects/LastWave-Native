package com.lastwave.app.diagnostics

import android.content.Context
import android.os.Process
import java.io.File

/**
 * Tracks whether the current session ended on its own terms.
 *
 * Android usually kills a process outright — a low-memory kill, a native
 * segfault, or the user swiping the app away — and none of those produce a
 * Java stack trace. Writing "running" at startup and "clean" at shutdown means
 * that finding a still-running marker on the *next* launch is proof the previous
 * session never got to shut down cleanly.
 *
 * This is deliberately not called a crash: the marker cannot distinguish a
 * system kill from a user swiping the app away, and [CrashType.UNKNOWN_TERMINATION]
 * says so rather than overclaiming.
 */
object SessionMarker {

    private const val FILE_NAME = "session.state"
    private const val RUNNING = "running"
    private const val CLEAN = "clean"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun write(context: Context, state: String) {
        runCatching {
            file(context).writeText("$state pid=${Process.myPid()} at=${System.currentTimeMillis()}")
        }
    }

    /** Marks the current session as in progress. Called during `attachBaseContext`. */
    fun markRunning(context: Context) = write(context, RUNNING)

    /** Marks the current session as shut down deliberately. */
    fun markClean(context: Context) = write(context, CLEAN)

    /**
     * Consumes any marker left by a previous session.
     *
     * Returns the recorded marker when that session never shut down cleanly, or
     * null when it did (or when there is no marker at all). The marker is
     * always removed so a session is only ever reported once.
     */
    fun takeUncleanSession(context: Context): String? = runCatching {
        val marker = file(context)
        if (!marker.exists()) return@runCatching null
        val contents = marker.readText()
        marker.delete()
        if (contents.startsWith(RUNNING)) contents else null
    }.getOrNull()
}