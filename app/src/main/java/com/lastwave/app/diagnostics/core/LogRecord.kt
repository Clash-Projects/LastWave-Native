package com.lastwave.app.diagnostics.core

/**
 * One log event, captured at the call site and rendered to a file line by
 * [LogFormatter].
 *
 * Deliberately free of any Android dependency so the formatting and retention
 * rules can be exercised on a plain JVM.
 */
data class LogRecord(
    val epochMillis: Long,
    val level: LogLevel,
    val tag: String,
    val threadName: String,
    val message: String,
    val throwable: Throwable? = null,
)