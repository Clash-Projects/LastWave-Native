package com.lastwave.app.diagnostics.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded hand-off between log call sites and the writer thread.
 *
 * The app logs from real-time audio callbacks, so [offer] must never block. When
 * the queue is full the *newest* record is dropped rather than the oldest: the
 * lines leading up to a crash are the most valuable data in the file, and under
 * a storm they are what has already been accepted.
 *
 * Drops are counted rather than discarded silently, so a log that lost data says
 * so — see [takeDroppedCount].
 */
class LogQueue(private val capacity: Int) {

    private val queue = ArrayBlockingQueue<LogRecord>(capacity)
    private val dropped = AtomicLong(0)

    /** Number of records waiting to be written. */
    val size: Int get() = queue.size

    /** Total records dropped since the last [takeDroppedCount]. */
    val droppedCount: Long get() = dropped.get()

    /** Enqueues [record] without ever blocking. Returns false when dropped. */
    fun offer(record: LogRecord): Boolean {
        val accepted = queue.offer(record)
        if (!accepted) dropped.incrementAndGet()
        return accepted
    }

    /**
     * Removes and returns up to [max] queued records, oldest first.
     * Never blocks: returns immediately with whatever is already buffered.
     */
    fun drain(max: Int): List<LogRecord> {
        if (max <= 0) return emptyList()
        val batch = ArrayList<LogRecord>(minOf(max, capacity))
        queue.drainTo(batch, max)
        return batch
    }

    /**
     * Returns the drop count and resets it, so a burst is reported once when the
     * writer next catches up rather than on every subsequent write.
     */
    fun takeDroppedCount(): Long = dropped.getAndSet(0)
}