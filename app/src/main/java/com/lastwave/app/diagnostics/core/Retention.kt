package com.lastwave.app.diagnostics.core

import java.io.File

/**
 * How much history LastWave keeps on disk.
 *
 * Both channels are bounded: the user gets a fixed, predictable amount of
 * history and the app cannot fill a user's storage no matter how badly it
 * misbehaves. Every file name embeds a lexicographically sortable timestamp, so
 * sorting by name *is* sorting by time — which is what [prune] relies on.
 */
object Retention {

    /** Distinct crashes retained, newest first. */
    const val KEEP_CRASHES = 10

    /** Rotated operational log files retained, including the active one. */
    const val KEEP_LOG_FILES = 5

    /** Size at which the active log rotates. */
    const val MAX_LOG_BYTES = 256L * 1024

    /** Startup launches retained in the trail. */
    const val KEEP_LAUNCHES = 10

    /** Operational log entries captured as the lead-up inside a crash report. */
    const val CRASH_LEAD_UP_LINES = 40

    /** Delete every file matching [prefix] beyond the newest [keep], oldest first. */
    fun prune(directory: File, prefix: String, keep: Int): List<File> {
        if (keep < 0) return emptyList()
        val candidates = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.sortedByDescending { it.name }
            ?: return emptyList()

        val excess = candidates.drop(keep)
        excess.forEach { it.delete() }
        return excess
    }
}