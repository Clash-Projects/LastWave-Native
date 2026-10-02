package com.lastwave.app.diagnostics.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring of recently formatted lines that gets embedded in a crash report as
 * its lead-up. This is the data that makes a crash actionable: the lines just
 * before the failure are where the cause usually is.
 */
class LogTailTest {

    @Test
    fun `keeps lines in arrival order`() {
        val tail = LogTail(capacity = 3)

        listOf("a", "b", "c").forEach { tail.add(it) }

        assertEquals(listOf("a", "b", "c"), tail.snapshot())
    }

    @Test
    fun `evicts the oldest line once full`() {
        val tail = LogTail(capacity = 2)

        listOf("a", "b", "c").forEach { tail.add(it) }

        assertEquals(listOf("b", "c"), tail.snapshot())
    }

    @Test
    fun `holds the configured number of lines`() {
        val tail = LogTail(capacity = Retention.CRASH_LEAD_UP_LINES)

        repeat(500) { tail.add("line $it") }

        assertEquals(Retention.CRASH_LEAD_UP_LINES, tail.snapshot().size)
    }

    @Test
    fun `returns the newest lines when there are more than requested`() {
        val tail = LogTail(capacity = 10)
        repeat(10) { tail.add("line $it") }

        assertEquals(listOf("line 8", "line 9"), tail.snapshot(last = 2))
    }

    @Test
    fun `is empty before anything is added`() {
        assertEquals(emptyList<String>(), LogTail(capacity = 4).snapshot())
    }

    @Test
    fun `survives concurrent writers`() {
        val tail = LogTail(capacity = 64)
        val threads = (0 until 8).map { t ->
            Thread { repeat(100) { tail.add("t$t-$it") } }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(tail.snapshot().size <= 64)
    }

    @Test
    fun `clear empties the ring`() {
        val tail = LogTail(capacity = 4)
        tail.add("a")

        tail.clear()

        assertTrue(tail.snapshot().isEmpty())
    }
}