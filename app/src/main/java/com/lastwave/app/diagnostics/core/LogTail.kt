package com.lastwave.app.diagnostics.core

import java.util.ArrayDeque

/**
 * Bounded in-memory ring of the most recently formatted log lines.
 *
 * A crash report is far more useful with the lines that preceded it: the cause
 * of a failure is usually visible in the trail just before the throw. This ring
 * is what makes that trail available at the moment the process is dying, when
 * the on-disk log may still be sitting in a write buffer.
 *
 * Cheap enough to add to on every record — it is a single synchronized deque
 * append, and no allocation beyond the line string.
 */
class LogTail(private val capacity: Int = Retention.CRASH_LEAD_UP_LINES) {

    private val lock = Any()
    private val lines = ArrayDeque<String>(capacity)

    /** Appends [line], evicting the oldest entry when full. */
    fun add(line: String) {
        synchronized(lock) {
            if (lines.size >= capacity) lines.pollFirst()
            lines.addLast(line)
        }
    }

    /** The retained lines, oldest first, limited to the newest [last]. */
    fun snapshot(last: Int = Int.MAX_VALUE): List<String> = synchronized(lock) {
        if (last >= lines.size) lines.toList() else lines.toList().takeLast(last)
    }

    fun clear() = synchronized(lock) { lines.clear() }
}