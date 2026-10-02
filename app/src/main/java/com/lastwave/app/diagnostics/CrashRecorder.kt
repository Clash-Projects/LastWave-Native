package com.lastwave.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import com.lastwave.app.diagnostics.core.LogTail
import com.lastwave.app.diagnostics.core.Retention
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one self-contained file per crash and keeps the [Retention.KEEP_CRASHES]
 * most recent.
 *
 * Two properties make these files worth having:
 *
 * 1. **They outlive the process.** Each crash is a separate file written
 *    synchronously during the failure, so nothing depends on a queue draining,
 *    on logcat being readable, or on the next launch to collect anything.
 * 2. **They carry their own context** — app version, device, memory, storage,
 *    the failing thread, the stack, and the log lines that preceded the failure.
 *    Without that lead-up a stack trace on its own usually cannot be reproduced.
 *
 * Nothing in here may throw: a failure to record a crash must never replace the
 * crash the user actually hit, so every step is guarded.
 */
object CrashRecorder {

    private const val DIRECTORY = "diagnostics/crashes"
    private const val PREFIX = "crash-"
    private const val SUFFIX = ".txt"
    private const val RULE = "================================================================"
    private const val STACK_FRAMES = 48

    private val stampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var tail: LogTail? = null

    private val lock = Any()

    fun install(context: Context, tail: LogTail) {
        // Called from attachBaseContext, where applicationContext can be null
        // because the Application is not registered yet. The passed context is
        // the Application itself, so it is safe to retain.
        this.appContext = context.applicationContext ?: context
        this.tail = tail
    }

    fun crashDirectory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    /** Retained crash files, newest first. */
    fun crashFiles(context: Context): List<File> = crashDirectory(context)
        .listFiles()
        ?.filter { it.isFile && it.name.startsWith(PREFIX) }
        ?.sortedByDescending { it.name }
        ?: emptyList()

    fun delete(context: Context, file: File): Boolean = file.delete()

    fun deleteAll(context: Context): Int {
        val files = crashFiles(context)
        files.forEach { it.delete() }
        return files.size
    }

    /**
     * Records [type] as a crash, pruning older records beyond the retention
     * limit. Safe to call from any thread; the crash path calls it while the
     * process is already unrecoverable, so it never defers work.
     */
    fun record(
        type: CrashType,
        thread: Thread? = null,
        throwable: Throwable? = null,
        detail: String? = null,
    ) {
        val context = appContext ?: return
        runCatching { writeReport(context, type, thread, throwable, detail) }
    }

    private fun writeReport(
        context: Context,
        type: CrashType,
        thread: Thread?,
        throwable: Throwable?,
        detail: String?,
    ) = synchronized(lock) {
        val directory = crashDirectory(context)
        val now = System.currentTimeMillis()
        val file = File(directory, "$PREFIX${stamp(now)}-${Process.myPid()}$SUFFIX")
        val leadUp = tail?.snapshot(Retention.CRASH_LEAD_UP_LINES).orEmpty()

        file.writeText(buildString {
            appendLine(RULE)
            appendLine(" LastWave crash report")
            appendLine(RULE)
            appendLine("Crash type   : ${type.label}")
            appendLine("Recorded at  : ${stamp(now)}")
            appendLine("App version  : ${appVersion(context)}")
            appendLine("Process      : pid=${Process.myPid()}")
            appendLine("Thread       : ${thread?.name ?: "n/a"}")
            appendLine("Device       : ${Build.MANUFACTURER} ${Build.MODEL}  sdk=${Build.VERSION.SDK_INT}  release=${Build.VERSION.RELEASE}  abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine("Memory       : ${memory(context)}")
            appendLine("Storage      : ${storage(context)}")

            if (!detail.isNullOrBlank()) {
                appendLine()
                appendLine("--- detail ---")
                appendLine(detail)
            }

            if (throwable != null) {
                appendLine()
                appendLine("--- message ---")
                appendLine(throwable.toString())
                appendLine()
                appendLine("--- stack trace ---")
                appendAll(throwable.stackTrace.take(STACK_FRAMES), "at ")
            }

            appendLine()
            appendLine("--- log lead-up (last ${leadUp.size} entries) ---")
            appendAll(leadUp)
            appendLine()
            appendLine("--- end ---")
        })

        Retention.prune(directory, PREFIX, Retention.KEEP_CRASHES)
    }

    private fun StringBuilder.appendAll(entries: List<Any>, prefix: String = "") {
        entries.forEach { appendLine(if (prefix.isEmpty()) it.toString() else "$prefix$it") }
    }

    private fun stamp(millis: Long): String = stampFormat.get()!!.format(Date(millis))

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrElse { "unknown" }

    private fun memory(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@runCatching "unknown"
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        val availMb = info.availMem / (1024 * 1024)
        "avail=${availMb}MB/${totalMb}MB  lowMemory=${info.lowMemory}"
    }.getOrElse { "unknown" }

    private fun storage(context: Context): String = runCatching {
        val stats = StatFs(context.filesDir.absolutePath)
        "free=${stats.availableBytes / (1024 * 1024 * 1024)}GB"
    }.getOrElse { "unknown" }
}