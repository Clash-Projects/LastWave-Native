package com.lastwave.app.diagnostics.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LogFormatterTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun formatter() = LogFormatter(utc)

    /** 2026-10-02 14:31:05.123 UTC */
    private val instant = 1_790_951_465_123L

    @Test
    fun `renders a single-line record in fixed columns`() {
        val line = formatter().line(
            LogRecord(
                epochMillis = instant,
                level = LogLevel.WARN,
                tag = "MusicPlayer",
                threadName = "main",
                message = "Route rebuild after device change",
            ),
        )

        assertEquals(
            "2026-10-02 14:31:05.123  W  MusicPlayer           main  Route rebuild after device change",
            line,
        )
    }

    @Test
    fun `pads short tags so every message starts at the same column`() {
        val shortTag = formatter().line(
            LogRecord(instant, LogLevel.WARN, "AB", "main", "one"),
        )
        val longTag = formatter().line(
            LogRecord(instant, LogLevel.WARN, "ABCDEFGHIJKLMNOPQRST", "main", "one"),
        )

        assertEquals(shortTag.indexOf("one"), longTag.indexOf("one"))
    }

    @Test
    fun `truncates a tag longer than the column`() {
        val line = formatter().line(
            LogRecord(instant, LogLevel.WARN, "A".repeat(40), "main", "msg"),
        )

        assertTrue(line.contains("A".repeat(19) + "…"))
        assertTrue(!line.contains("A".repeat(20)))
    }

    @Test
    fun `collapses embedded newlines so one event stays on one line`() {
        val line = formatter().line(
            LogRecord(instant, LogLevel.ERROR, "Tag", "main", "first\nsecond\r\nthird"),
        )

        assertEquals(1, line.lines().size)
        assertTrue(line.endsWith("first⏎second⏎third"))
    }

    @Test
    fun `renders a throwable as indented continuation lines`() {
        val error = IllegalStateException("sink closed").apply {
            stackTrace = arrayOf(StackTraceElement("com.example.Sink", "write", "Sink.kt", 214))
        }

        val line = formatter().lines(
            LogRecord(
                epochMillis = instant,
                level = LogLevel.ERROR,
                tag = "NativeAudioSink",
                threadName = "audio",
                message = "Underrun: 41 frames dropped",
                throwable = error,
            ),
        )

        assertEquals(3, line.size)
        assertTrue(line[0].startsWith("2026-10-02 14:31:05.123"))
        assertTrue(line[0].endsWith("Underrun: 41 frames dropped"))
        assertEquals("    java.lang.IllegalStateException: sink closed", line[1])
        assertEquals("        at com.example.Sink.write(Sink.kt:214)", line[2])
    }

    @Test
    fun `renders a throwable with no message as class name alone`() {
        val error = IllegalStateException().apply {
            stackTrace = arrayOf(StackTraceElement("com.example.Sink", "write", "Sink.kt", 214))
        }

        val line = formatter().lines(LogRecord(instant, LogLevel.ERROR, "T", "main", "m", error))

        assertEquals("    java.lang.IllegalStateException", line[1])
    }

    @Test
    fun `caps rendered stack frames so one failure cannot flood the log`() {
        val deep = RuntimeException("boom").apply {
            stackTrace = Array(300) { StackTraceElement("com.example.Foo", "bar$it", "Foo.kt", it + 1) }
        }

        val line = formatter().lines(
            LogRecord(instant, LogLevel.ERROR, "T", "main", "m", deep),
        )

        val frameLines = line.count { it.startsWith("        at ") }
        assertEquals(LogFormatter.MAX_STACK_FRAMES, frameLines)
    }
}