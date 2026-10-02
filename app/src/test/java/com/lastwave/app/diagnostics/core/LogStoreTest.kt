package com.lastwave.app.diagnostics.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val formatter = LogFormatter(java.util.TimeZone.getTimeZone("UTC"))

    private fun store(
        maxBytes: Long = Retention.MAX_LOG_BYTES,
        keepFiles: Int = Retention.KEEP_LOG_FILES,
        header: String = "",
        directory: File = temp.root,
    ) = LogStore(
        directory = directory,
        formatter = formatter,
        maxBytes = maxBytes,
        keepFiles = keepFiles,
        header = header,
    )

    private fun record(
        message: String,
        atMillis: Long = 1_790_951_465_123L,
        level: LogLevel = LogLevel.WARN,
    ) = LogRecord(atMillis, level, "Tag", "main", message)

    @Test
    fun `creates the log directory when it does not exist`() {
        val nested = File(temp.root, "diagnostics/logs")

        store(directory = nested).append(listOf(record("hello")))

        assertTrue(nested.isDirectory)
    }

    @Test
    fun `writes the record to the active file`() {
        val store = store()

        store.append(listOf(record("Route rebuild")))

        assertTrue(store.activeFile()!!.readText().contains("Route rebuild"))
    }

    @Test
    fun `writes one physical line per record with no trailing blank`() {
        val store = store()
        store.append(listOf(record("one"), record("two")))

        val lines = store.activeFile()!!.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("one"))
        assertTrue(lines[1].endsWith("two"))
    }

    @Test
    fun `writes the header only when the file is created`() {
        val first = store(header = "LastWave 4.2.2 session start")
        first.append(listOf(record("first")))
        assertTrue(first.activeFile()!!.readText().contains("LastWave 4.2.2 session start"))

        // A second store over the same directory must not re-emit the header.
        val second = store(header = "LastWave 4.2.2 session start")
        second.append(listOf(record("second")))

        val combined = second.activeFile()!!.readText()
        assertEquals(1, combined.split("LastWave 4.2.2 session start").size - 1)
    }

    @Test
    fun `rotates to a new file once the size cap would be exceeded`() {
        val store = store(maxBytes = 400)
        store.append(listOf(record("first")))
        val first = store.activeFile()

        repeat(20) { store.append(listOf(record("line $it"))) }

        assertNotEquals("never rotated", first!!.name, store.activeFile()!!.name)
        assertTrue(store.files().size > 1)
    }

    @Test
    fun `does not rotate while under the size cap`() {
        val store = store(maxBytes = 100_000)
        store.append(listOf(record("first")))
        val first = store.activeFile()

        repeat(10) { store.append(listOf(record("line $it"))) }

        assertEquals(first!!.name, store.activeFile()!!.name)
    }

    @Test
    fun `prunes old files beyond the retention limit`() {
        val store = store(maxBytes = 200, keepFiles = 2)

        repeat(40) { store.append(listOf(record("line $it"))) }

        assertTrue(
            "kept ${store.files().size} files, expected at most 2",
            store.files().size <= 2,
        )
    }

    @Test
    fun `lists files newest first`() {
        val store = store(maxBytes = 200)

        repeat(30) { store.append(listOf(record("line $it"))) }

        val names = store.files().map { it.name }
        assertEquals(names.sortedDescending(), names)
    }

    @Test
    fun `appends to an existing log after a restart instead of truncating it`() {
        val before = store()
        before.append(listOf(record("from first session")))

        val after = store()
        after.append(listOf(record("from second session")))

        val text = after.activeFile()!!.readText()
        assertTrue("earlier session was lost", text.contains("from first session"))
        assertTrue(text.contains("from second session"))
    }

    @Test
    fun `reports no active file before the first append`() {
        assertEquals(null, store().activeFile())
    }

    @Test
    fun `survives a record whose message is empty`() {
        val store = store()
        store.append(listOf(record("")))

        assertTrue(store.activeFile()!!.readText().isNotEmpty())
    }

    @Test
    fun `keeps the total on disk within the configured retention`() {
        val store = store(maxBytes = 150, keepFiles = 3)

        repeat(100) { store.append(listOf(record("line $it"))) }

        val bytes = temp.root.listFiles()!!.sumOf { it.length() }
        assertTrue("on-disk bytes $bytes exceeded the cap", bytes <= 3 * 150 + 400)
    }
}