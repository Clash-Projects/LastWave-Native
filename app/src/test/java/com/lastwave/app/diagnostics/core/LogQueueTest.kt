package com.lastwave.app.diagnostics.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogQueue] is the boundary that keeps logging off the audio thread's critical
 * path: [offer] must never block, and must degrade by dropping rather than by
 * stalling the caller.
 */
class LogQueueTest {

    private fun record(message: String) =
        LogRecord(0L, LogLevel.WARN, "T", "main", message)

    @Test
    fun `accepts a record while below capacity`() {
        val queue = LogQueue(capacity = 2)

        assertTrue(queue.offer(record("a")))
        assertEquals(0, queue.droppedCount)
    }

    @Test
    fun `drops the newest record once full instead of blocking`() {
        val queue = LogQueue(capacity = 1)

        assertTrue(queue.offer(record("a")))
        assertFalse(queue.offer(record("b")))
    }

    @Test
    fun `keeps the oldest records when dropping`() {
        val queue = LogQueue(capacity = 2)

        queue.offer(record("a"))
        queue.offer(record("b"))
        queue.offer(record("c"))

        assertEquals(listOf("a", "b"), queue.drain(Int.MAX_VALUE).map { it.message })
    }

    @Test
    fun `counts every dropped record`() {
        val queue = LogQueue(capacity = 1)

        queue.offer(record("a"))
        repeat(4) { queue.offer(record("drop$it")) }

        assertEquals(4L, queue.droppedCount)
    }

    @Test
    fun `drains in insertion order`() {
        val queue = LogQueue(capacity = 8)

        listOf("a", "b", "c").forEach { queue.offer(record(it)) }

        assertEquals(listOf("a", "b", "c"), queue.drain(Int.MAX_VALUE).map { it.message })
    }

    @Test
    fun `drains at most the requested number and leaves the rest queued`() {
        val queue = LogQueue(capacity = 8)

        listOf("a", "b", "c", "d").forEach { queue.offer(record(it)) }

        assertEquals(listOf("a", "b"), queue.drain(2).map { it.message })
        assertEquals(listOf("c", "d"), queue.drain(Int.MAX_VALUE).map { it.message })
    }

    @Test
    fun `reports how many records are waiting`() {
        val queue = LogQueue(capacity = 8)

        listOf("a", "b").forEach { queue.offer(record(it)) }

        assertEquals(2, queue.size)
    }

    @Test
    fun `takeDroppedCount resets the counter so a storm is reported once`() {
        val queue = LogQueue(capacity = 1)

        queue.offer(record("a"))
        queue.offer(record("b"))
        assertEquals(1L, queue.takeDroppedCount())

        assertEquals(0L, queue.droppedCount)
    }

    @Test
    fun `stays within capacity under concurrent producers`() {
        val queue = LogQueue(capacity = 64)
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(200) { i -> queue.offer(record("t$t-$i")) }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("queue exceeded capacity", queue.size <= 64)
        assertEquals(1600L - queue.drain(Int.MAX_VALUE).size, queue.droppedCount)
    }
}