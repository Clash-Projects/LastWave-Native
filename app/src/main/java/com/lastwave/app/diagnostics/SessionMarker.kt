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

    // Last known activity of this session, written into the running marker
    // so an unclean end can be told apart: killed while playing music with
    // the foreground service up (a real problem) vs. idle in the background
    // or swiped away (normal Android/One UI memory management).
    @Volatile private var playing = false
    @Volatile private var trackLoaded = false
    @Volatile private var uiVisible = false
    @Volatile private var lastState = RUNNING

    private fun write(context: Context, state: String) {
        lastState = state
        runCatching {
            val activity = if (state == RUNNING) {
                " playing=$playing trackLoaded=$trackLoaded uiVisible=$uiVisible"
            } else ""
            file(context).writeText("$state pid=${Process.myPid()} at=${System.currentTimeMillis()}$activity")
        }
    }

    /** Marks the current session as in progress. Called during `attachBaseContext`. */
    fun markRunning(context: Context) = write(context, RUNNING)

    /**
     * Records the session's current activity in the running marker. Cheap
     * (one tiny file write) and only called when one of the flags changes.
     */
    fun noteActivity(
        context: Context,
        playing: Boolean = this.playing,
        trackLoaded: Boolean = this.trackLoaded,
        uiVisible: Boolean = this.uiVisible,
    ) {
        if (playing == this.playing && trackLoaded == this.trackLoaded && uiVisible == this.uiVisible) return
        this.playing = playing
        this.trackLoaded = trackLoaded
        this.uiVisible = uiVisible
        if (lastState == RUNNING) write(context, RUNNING)
    }

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