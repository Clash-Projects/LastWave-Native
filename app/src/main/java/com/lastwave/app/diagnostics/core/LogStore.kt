package com.lastwave.app.diagnostics.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Appends formatted [LogRecord]s to a rotating set of files under [directory].
 *
 * Two properties matter here:
 *
 * 1. **It appends.** Opening an existing log resumes writing to it, so an app
 *    restart never destroys history.
 * 2. **It is bounded.** The active file rotates at [maxBytes] and old rotations
 *    are pruned via [Retention], so a misbehaving app cannot fill the user's
 *    storage.
 *
 * All file names carry a lexicographically sortable timestamp, so newest-first
 * order is a plain string sort.
 *
 * Not thread-safe by design — it is owned exclusively by the single writer
 * thread, which is what keeps disk I/O off the audio callback path.
 */
class LogStore(
    private val directory: File,
    private val formatter: LogFormatter = LogFormatter(),
    private val maxBytes: Long = Retention.MAX_LOG_BYTES,
    private val keepFiles: Int = Retention.KEEP_LOG_FILES,
    private val header: String = "",
    private val fileNamePrefix: String = FILE_PREFIX,
) {

    companion object {
        const val FILE_PREFIX = "log-"

        private const val SUFFIX = ".txt"

        /**
         * Fixed-width so lexicographic order equals numeric order. A variable-width
         * counter would sort "10" before "9", and a counter appended after the
         * extension would sort "_1" before ".txt" — either way filename order
         * stops matching creation order and pruning deletes the wrong file.
         */
        private const val SEQUENCE_DIGITS = 6

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS")
    }

    private var writer: BufferedWriter? = null
    private var current: File? = null
    private var bytesWritten: Long = 0
    private var sequence: Int = highestExistingSequence() + 1

    /** The file currently being appended to, or null before the first append. */
    fun activeFile(): File? = current

    /** Every retained log file, newest first. */
    fun files(): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.name.startsWith(fileNamePrefix) }
        ?.sortedByDescending { it.name }
        ?: emptyList()

    /** Appends [records] as one batch, rotating as needed. */
    fun append(records: List<LogRecord>) {
        if (records.isEmpty()) return
        if (!directory.isDirectory) directory.mkdirs()

        records.forEach { record ->
            val lines = formatter.lines(record)
            val payload = lines.joinToString(separator = "\n", postfix = "\n")
            val payloadBytes = payload.toByteArray(Charsets.UTF_8).size.toLong()

            // Rotate *before* the write that would breach the cap, never after,
            // so no single file can exceed it by more than one record.
            if (current != null && bytesWritten + payloadBytes > maxBytes) {
                rotate(record.epochMillis)
            }
            if (current == null) {
                openForAppend(record.epochMillis)
            }

            writer?.apply {
                write(payload)
                bytesWritten += payloadBytes
            }
        }
        writer?.flush()
    }

    /** Forces buffered output to disk. Safe to call from any thread. */
    fun flush() {
        writer?.flush()
    }

    /** Closes the active file, leaving disk state consistent. */
    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    /** Reopens [record]'s newest existing file, or creates one if none exists. */
    private fun openForAppend(atMillis: Long) {
        val existing = files().firstOrNull()
        val target = existing ?: createFile(atMillis)
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8))
        current = target
        bytesWritten = target.length()

        if (existing == null && header.isNotBlank()) {
            writer?.apply {
                append(header)
                append("\n\n")
                flush()
            }
            bytesWritten = target.length()
        }
    }

    /** Closes the active file and starts a fresh one named for [atMillis]. */
    private fun rotate(atMillis: Long) {
        close()
        writer = null
        current = null
        bytesWritten = 0

        val target = createFile(atMillis)
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(target, false), Charsets.UTF_8))
        current = target

        if (header.isNotBlank()) {
            writer?.apply {
                append(header)
                append("\n\n")
                flush()
            }
            bytesWritten = target.length()
        }

        Retention.prune(directory, fileNamePrefix, keepFiles)
    }

    /**
     * Creates a new file whose name sorts after every file this store has already
     * produced, using a monotonically increasing sequence so that filename order
     * always equals creation order.
     */
    private fun createFile(atMillis: Long): File {
        val stamp = STAMP.format(Instant.ofEpochMilli(atMillis).atZone(ZoneOffset.UTC))
        val name = "$fileNamePrefix$stamp-${sequence.toString().padStart(SEQUENCE_DIGITS, '0')}$SUFFIX"
        sequence++
        return File(directory, name).also { it.createNewFile() }
    }

    /** Highest sequence already on disk, so a restarted store never reuses one. */
    private fun highestExistingSequence(): Int = files()
        .mapNotNull { sequenceOf(it.name) }
        .maxOrNull() ?: 0

    private fun sequenceOf(fileName: String): Int? = fileName
        .removePrefix(fileNamePrefix)
        .removeSuffix(SUFFIX)
        .substringAfterLast('-')
        .toIntOrNull()
}