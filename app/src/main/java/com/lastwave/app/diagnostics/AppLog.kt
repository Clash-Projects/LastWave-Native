package com.lastwave.app.diagnostics

import android.content.Context
import android.util.Log
import com.lastwave.app.diagnostics.core.LogFormatter
import com.lastwave.app.diagnostics.core.LogLevel
import com.lastwave.app.diagnostics.core.LogQueue
import com.lastwave.app.diagnostics.core.LogRecord
import com.lastwave.app.diagnostics.core.LogStore
import com.lastwave.app.diagnostics.core.LogTail

/**
 * The logging entry point for the whole app.
 *
 * Every call does two things: it always reaches logcat, so an attached debugger
 * and `adb logcat` behave exactly as before, and it is queued for disk when the
 * level is severe enough per [LogConfig].
 *
 * The disk hand-off is a non-blocking enqueue. Nothing here opens a file,
 * formats a stack, or waits on I/O, which is what makes it safe to call from an
 * audio callback — see [LogQueue].
 *
 * Safe to call before [install]: it degrades to plain logcat rather than
 * throwing, so a failure during early startup cannot cascade.
 */
object AppLog {

    const val QUEUE_CAPACITY = 4096

    @Volatile
    private var sink: Sink? = null

    private class Sink(
        val queue: LogQueue,
        val writer: LogWriter,
        val tail: LogTail,
    )

    val tail: LogTail? get() = sink?.tail

    /** Wires up disk logging. Called once from the Application's `attachBaseContext`. */
    fun install(context: Context) {
        runCatching {
            // Diagnostics installs from attachBaseContext, where the Application
            // may not be registered yet and applicationContext can be null.
            // Falling back to the passed context is safe: it *is* the
            // Application, so holding it leaks nothing.
            val appContext = context.applicationContext ?: context
            LogConfig.install(appContext)

            val tail = LogTail()
            val store = LogStore(
                directory = appContext.filesDir.resolve(DIRECTORY),
                formatter = LogFormatter(),
                header = header(appContext),
            )
            val queue = LogQueue(QUEUE_CAPACITY)
            val writer = LogWriter(store = store, queue = queue, tail = tail)

            CrashRecorder.install(appContext, tail)

            sink = Sink(queue, writer, tail)
            writer.start()

            writer.flush()
            AppLog.i(TAG, "Diagnostics ready; disk logging from ${LogConfig.diskMinimum().name}")
        }
    }

    fun v(tag: String, message: String, throwable: Throwable? = null) =
        record(LogLevel.VERBOSE, tag, message, throwable)

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        record(LogLevel.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        record(LogLevel.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        record(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        record(LogLevel.ERROR, tag, message, throwable)

    /**
     * Writes everything queued right now. Called on process shutdown and
     * immediately before a crash is recorded, so nothing queued is lost.
     */
    fun flush() {
        runCatching { sink?.writer?.flush() }
    }

    private fun record(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        // logcat first: it must keep working even if disk logging is not installed.
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message, throwable)
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }

        val target = sink ?: return
        if (!LogConfig.shouldPersist(level)) return
        target.queue.offer(
            LogRecord(
                epochMillis = System.currentTimeMillis(),
                level = level,
                tag = tag,
                threadName = Thread.currentThread().name,
                message = message,
                throwable = throwable,
            ),
        )
    }

    private fun header(context: Context): String = buildString {
        appendLine("LastWave ${versionName(context)} — operational log")
        appendLine("Session started ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrElse { "unknown" }

    private const val TAG = "AppLog"
    private const val DIRECTORY = "diagnostics/logs"
}