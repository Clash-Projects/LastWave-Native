package com.lastwave.app.diagnostics

import com.lastwave.app.diagnostics.core.LogFormatter
import com.lastwave.app.diagnostics.core.LogQueue
import com.lastwave.app.diagnostics.core.LogRecord
import com.lastwave.app.diagnostics.core.LogStore
import com.lastwave.app.diagnostics.core.LogTail

/**
 * The single thread that touches the log files.
 *
 * Everything funnels through one queue and one writer thread so that no caller
 * — least of all an audio callback — ever waits on disk. Records are written in
 * batches to keep the number of write syscalls down, and the flushed text is
 * mirrored into [tail] so a crash can report the lines that led up to it.
 *
 * When the queue overflows, the writer reports the loss as a warning rather
 * than letting a truncated log look complete.
 */
class LogWriter(
    private val store: LogStore,
    private val queue: LogQueue,
    private val tail: LogTail,
    private val formatter: LogFormatter = LogFormatter(),
) {

    companion object {
        /** Records per batch — enough to amortise the write without adding latency. */
        private const val BATCH_SIZE = 64

        /** Longest the writer will sit idle before re-checking. */
        private const val IDLE_POLL_MILLIS = 200L
    }

    @Volatile
    private var running = false

    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread({ loop() }, "lastwave-log-writer").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    /**
     * Drains and writes everything queued right now, on the calling thread.
     *
     * The crash path and process shutdown depend on this: it is what makes a
     * record written moments before a failure actually reach the disk.
     */
    fun flush() {
        synchronized(this) {
            writeAvailable()
            store.flush()
        }
    }

    private fun loop() {
        while (running) {
            synchronized(this) {
                writeAvailable()
                store.flush()
            }
            try {
                Thread.sleep(IDLE_POLL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Writes everything currently queued. Caller must hold the monitor. */
    private fun writeAvailable() {
        val batch = queue.drain(BATCH_SIZE)
        if (batch.isEmpty()) {
            reportDropsIfAny()
            return
        }
        batch.forEach { record ->
            val lines = formatter.lines(record)
            tail.add(lines.first())
            store.append(listOf(record))
        }
        reportDropsIfAny()
    }

    /**
     * Surfaces a burst of dropped records once, rather than logging a warning
     * for every drop while already overloaded.
     */
    private fun reportDropsIfAny() {
        val dropped = queue.takeDroppedCount()
        if (dropped <= 0) return
        val notice = LogRecord(
            epochMillis = System.currentTimeMillis(),
            level = com.lastwave.app.diagnostics.core.LogLevel.WARN,
            tag = "LogWriter",
            threadName = Thread.currentThread().name,
            message = "Dropped $dropped log record(s) under load; the log is incomplete",
        )
        formatter.lines(notice).first().let { tail.add(it) }
        store.append(listOf(notice))
    }
}