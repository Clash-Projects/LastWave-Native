package com.lastwave.app.data.download

import java.io.File
import java.io.RandomAccessFile

/**
 * Detects CENC sample encryption in MP4-family files by walking the box
 * structure (never raw byte search: ciphertext randomly contains 'senc').
 *
 * A track with no sample-encryption boxes anywhere is genuinely clear audio
 * and needs no license, no CDM, no transcode — it plays in any player.
 * Used by the module download flow to skip the whole DRM chain when the
 * provider serves a track (or track version) unprotected.
 */
internal object Mp4EncryptionScanner {

    private val CONTAINERS = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "moof", "traf", "mfra",
        "edts", "mvex", "strk", "sinf", "schi",
    )
    private val PROTECTION_BOXES = setOf("senc", "saiz", "saio", "sbgp", "sgpd")

    fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                walk(raf, 0L, raf.length())
            }
        } catch (_: Exception) {
            // Unreadable/malformed: fail toward the verified DRM path, never
            // toward silent clear playback of a truncated file.
            true
        }
    }

    private fun walk(raf: RandomAccessFile, start: Long, end: Long): Boolean {
        var off = start
        val header = ByteArray(16)
        while (off + 8 <= end) {
            raf.seek(off)
            if (raf.read(header, 0, 8) != 8) return true
            var size = ((header[0].toInt() and 0xFF).toLong() shl 24) or
                ((header[1].toInt() and 0xFF).toLong() shl 16) or
                ((header[2].toInt() and 0xFF).toLong() shl 8) or
                (header[3].toInt() and 0xFF).toLong()
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerSize = 8L
            if (size == 1L) {
                if (off + 16 > end) return true
                if (raf.read(header, 8, 8) != 8) return true
                size = (0 until 8).fold(0L) { acc, i ->
                    (acc shl 8) or (header[8 + i].toInt() and 0xFF).toLong()
                }
                headerSize = 16L
            } else if (size == 0L) {
                size = end - off
            }
            if (size < headerSize || off + size > end) return true
            if (type in PROTECTION_BOXES) return true
            if (type in CONTAINERS && size > headerSize) {
                if (walk(raf, off + headerSize, off + size)) return true
            }
            if (size == 0L) break
            off += size
        }
        return false
    }
}
