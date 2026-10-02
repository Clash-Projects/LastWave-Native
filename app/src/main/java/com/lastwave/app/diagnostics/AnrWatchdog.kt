package com.lastwave.app.diagnostics

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches for the main thread stalling long enough to be an ANR.
 *
 * A handler post is a canary: if the main looper cannot run a trivial runnable
 * within [thresholdMillis], the thread is blocked. Android's own ANR machinery
 * then produces a system trace, but by then the evidence is gone and often the
 * user never sends anything. Capturing the main thread's stack *at the moment of
 * the stall* is what makes the report actionable.
 *
 * The watchdog deliberately does not kill the process — deciding that is
 * Android's job, not ours. It also re-arms only after the main thread responds,
 * so one stall yields one report rather than one per second.
 *
 * False positives are possible during genuinely heavy main-thread work. Each
 * report therefore records how long the stall lasted, so a reader can judge
 * whether it was a deadlock or simply slow work.
 */
class AnrWatchdog(
    private val thresholdMillis: Long = DEFAULT_THRESHOLD_MILLIS,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    private val onStall: (stalledMillis: Long, mainThreadStack: Array<StackTraceElement>) -> Unit,
) {

    companion object {
        /** Matches Android's input-dispatch ANR limit; short GC pauses will not trip it. */
        const val DEFAULT_THRESHOLD_MILLIS = 5_000L
        private const val DEFAULT_POLL_MILLIS = 1_000L
        private const val CHECK_GRANULARITY_MILLIS = 50L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var responded = true

    @Volatile
    private var running = false

    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread({ watch() }, "lastwave-anr-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun watch() {
        while (running) {
            responded = false
            val postedAt = SystemClock.uptimeMillis()
            mainHandler.post { responded = true }

            val deadline = postedAt + thresholdMillis
            while (running && !responded && SystemClock.uptimeMillis() < deadline) {
                try {
                    Thread.sleep(CHECK_GRANULARITY_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
            }

            if (running && !responded) {
                runCatching {
                    onStall(
                        SystemClock.uptimeMillis() - postedAt,
                        Looper.getMainLooper().thread.stackTrace,
                    )
                }
            }

            try {
                Thread.sleep(pollMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}