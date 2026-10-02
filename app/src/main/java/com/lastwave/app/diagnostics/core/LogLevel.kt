package com.lastwave.app.diagnostics.core

/**
 * Severity of a single log record, ordered so [priority] can be compared
 * directly to decide whether a record should reach disk.
 */
enum class LogLevel(val priority: Int, val symbol: Char) {
    VERBOSE(0, 'V'),
    DEBUG(1, 'D'),
    INFO(2, 'I'),
    WARN(3, 'W'),
    ERROR(4, 'E'),
    ;

    /** True when this record is at least as severe as [minimum]. */
    fun isAtLeast(minimum: LogLevel): Boolean = priority >= minimum.priority
}