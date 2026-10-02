package com.lastwave.app.diagnostics.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Renders a [LogRecord] into the fixed-column text that reaches disk.
 *
 * The columns exist so the file both reads cleanly in a viewer and greps
 * predictably from a terminal:
 *
 * ```
 * 2026-10-02 14:31:05.123  W  MusicPlayer          main  Route rebuild after device change
 * ```
 *
 * A throwable expands into indented continuation lines beneath the record, so
 * a stack trace never breaks the alignment of the records around it.
 *
 * The zone is injected rather than read from [TimeZone.getDefault] so that
 * formatting is deterministic under test.
 */
class LogFormatter(zone: TimeZone = TimeZone.getDefault()) {

    companion object {
        /** Tags wider than this are truncated so the message column stays put. */
        const val TAG_COLUMN = 20

        /** Frames beyond this are dropped; one failure must not flood the log. */
        const val MAX_STACK_FRAMES = 32

        private const val EXCEPTION_INDENT = "    "
        private const val FRAME_INDENT = "        "

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        /** Stand-in for a line break that would otherwise split a record. */
        private const val NEWLINE_MARKER = "⏎"
    }

    private val zoneId: ZoneId = zone.toZoneId()

    /** The record's own line, with the throwable omitted. */
    fun line(record: LogRecord): String {
        val timestamp = TIMESTAMP.format(Instant.ofEpochMilli(record.epochMillis).atZone(zoneId))
        return "$timestamp  ${record.level.symbol}  ${fitTag(record.tag)}  ${record.threadName}  ${flatten(record.message)}"
    }

    /** Pads a tag to the fixed column, truncating with an ellipsis when too wide. */
    private fun fitTag(tag: String): String = when {
        tag.length <= TAG_COLUMN -> tag.padEnd(TAG_COLUMN)
        else -> tag.take(TAG_COLUMN - 1) + "…"
    }

    /** The record's line followed by its indented throwable, if it has one. */
    fun lines(record: LogRecord): List<String> {
        val out = ArrayList<String>()
        out.add(line(record))
        val throwable = record.throwable ?: return out
        out.add(EXCEPTION_INDENT + throwable.toString().replace("\n", NEWLINE_MARKER))
        throwable.stackTrace.take(MAX_STACK_FRAMES).forEach { frame ->
            out.add(FRAME_INDENT + "at $frame")
        }
        return out
    }

    /** Collapses line breaks so a one-line message never breaks the columns. */
    private fun flatten(message: String): String = message
        .replace("\r\n", NEWLINE_MARKER)
        .replace("\n", NEWLINE_MARKER)
        .replace("\r", NEWLINE_MARKER)
}