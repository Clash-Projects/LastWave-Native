package com.lastwave.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal launch-stage breadcrumbs for instant-kill diagnosis.
 *
 *  A cold-start crash that escapes every handler (native kill, graphics-layer
 *  failure, pre-provider fault) leaves no stack anywhere. These markers are
 *  flushed to disk at each startup stage, so the next launch — or a
 *  diagnostics export — shows exactly how far the dead process got.
 *  Retention is [com.lastwave.app.diagnostics.core.Retention.KEEP_LAUNCHES];
 *  every call is exception-proof. */
object StartupTrail {

    private const val LOG_FILE_NAME = "lastwave_startup_trail.log"
    private val MAX_LAUNCHES_KEPT = com.lastwave.app.diagnostics.core.Retention.KEEP_LAUNCHES

    @Volatile private var logFile: File? = null

    @Synchronized
    fun begin(context: Context) {
        runCatching {
            val file = File(context.applicationInfo.dataDir, LOG_FILE_NAME).also { logFile = it }
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val previous = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull().orEmpty()
            val launches = previous.joinToString("\n").split("--- launch ").drop(1)
            val kept = launches.takeLast(MAX_LAUNCHES_KEPT - 1)
                .joinToString("") { "--- launch $it\n" }
            file.writeText("$kept--- launch $stamp pid=${android.os.Process.myPid()}\n")
        }
    }

    fun mark(stage: String) {
        runCatching {
            val file = logFile ?: return
            val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            synchronized(this@StartupTrail) {
                file.appendText("$stamp $stage\n")
            }
        }
    }

    fun readTail(maxLines: Int = 60): String = runCatching {
        val file = logFile ?: return "(startup trail unavailable)"
        readTailLines(file, maxLines)
    }.getOrElse { "(startup trail unreadable: ${it.message})" }

    /** Context-based read for callers (diagnostics export) that never stored the file handle. */
    fun readTail(context: Context, maxLines: Int = 60): String = runCatching {
        readTailLines(File(context.applicationInfo.dataDir, LOG_FILE_NAME), maxLines)
    }.getOrElse { "(startup trail unreadable: ${it.message})" }

    private fun readTailLines(file: File, maxLines: Int): String {
        if (!file.exists()) return "(no startup trail)"
        return file.readLines(Charsets.UTF_8).takeLast(maxLines)
            .joinToString("\n").ifBlank { "(empty startup trail)" }
    }
}
