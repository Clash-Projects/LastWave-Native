package com.lastwave.app.diagnostics.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(name: String) =
        temp.newFile(name).also { it.writeText("x") }

    @Test
    fun `deletes the oldest files beyond the limit`() {
        val excess = (1..3).map { write("crash-2026-10-0${it}T00-00-00-000_pid-1.txt") }
        val kept = (4..6).map { write("crash-2026-10-0${it}T00-00-00-000_pid-1.txt") }

        val deleted = Retention.prune(temp.root, "crash-", keep = 3)

        assertEquals(excess, deleted.sortedBy { it.name })
        assertEquals(kept, kept.filter { it.exists() }.sortedBy { it.name })
    }

    @Test
    fun `keeps exactly the limit and nothing more`() {
        repeat(4) { write("crash-2026-10-0${it + 1}T00-00-00-000_pid-1.txt") }

        Retention.prune(temp.root, "crash-", keep = 2)

        assertEquals(2, temp.root.listFiles()!!.size)
    }

    @Test
    fun `deletes nothing when the count is exactly at the limit`() {
        repeat(3) { write("crash-2026-10-0${it + 1}T00-00-00-000_pid-1.txt") }

        val deleted = Retention.prune(temp.root, "crash-", keep = 3)

        assertTrue(deleted.isEmpty())
        assertEquals(3, temp.root.listFiles()!!.size)
    }

    @Test
    fun `deletes nothing when under the limit`() {
        write("crash-2026-10-01T00-00-00-000_pid-1.txt")

        assertTrue(Retention.prune(temp.root, "crash-", keep = 10).isEmpty())
    }

    @Test
    fun `ignores files that do not match the prefix`() {
        write("crash-2026-10-01T00-00-00-000_pid-1.txt")
        val other = write("startup_trail.log")

        Retention.prune(temp.root, "crash-", keep = 0)

        assertTrue("unrelated file was deleted", other.exists())
    }

    @Test
    fun `treats the filename as the chronological order`() {
        // Lexicographic order must match time order for this pruning rule to be
        // correct, since filenames embed a sortable timestamp.
        val older = write("log-2026-10-01T09-00-00-000.txt")
        val newer = write("log-2026-10-02T09-00-00-000.txt")

        Retention.prune(temp.root, "log-", keep = 1)

        assertTrue(!older.exists())
        assertTrue(newer.exists())
    }

    @Test
    fun `survives a missing directory`() {
        val missing = File(temp.root, "nope")

        assertTrue(Retention.prune(missing, "crash-", keep = 10).isEmpty())
    }

    @Test
    fun `keeps the ten most recent crashes and no more`() {
        repeat(14) { write("crash-2026-10-01T00-00-${"%02d".format(it)}-000_pid-1.txt") }

        Retention.prune(temp.root, "crash-", keep = Retention.KEEP_CRASHES)

        assertEquals(Retention.KEEP_CRASHES, temp.root.listFiles()!!.size)
    }
}