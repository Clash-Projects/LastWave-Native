package com.lastwave.app.data.download

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Losslessly lifts FLAC frames out of an ISO-BMFF (fMP4) container into a
 * native `.flac` stream.
 *
 * Tidal's lossless DASH carries FLAC inside MP4 (`mimeType="audio/mp4"`,
 * `codecs="flac"`). Saving those bytes as `.m4a` is wrong twice over: users who
 * asked for FLAC get an `.m4a` that players and tag tools report as AAC, and the
 * container is not a valid standalone FLAC file at all. This remux writes the
 * original FLAC metadata block(s) from the `dfLa` box followed by the raw frames
 * from every `mdat` payload. **Nothing is decoded or re-encoded**, so a 24-bit
 * hi-res stream stays bit-exact.
 *
 * Layout observed on real Tidal streams:
 * ```
 * init segment : ftyp | moov{ ... stsd{ fLaC{ dfLa{ version/flags | METADATA_BLOCKS } } } }
 * media segment: moof | mdat{ FLAC frames }
 * ```
 * The download concatenates the init segment and every media segment into one
 * temp file, so a single sequential box walk yields both pieces.
 */
internal object Mp4FlacRemuxer {
    private const val TAG = "Mp4FlacRemuxer"
    private const val COPY_BUFFER = 64 * 1024
    private const val FLAC_MAGIC = "fLaC"
    private const val STREAMINFO_TYPE = 0
    private const val STREAMINFO_LENGTH = 34
    private const val LAST_METADATA_BLOCK_FLAG = 0x80
    /** `moov` is a few hundred bytes on real streams; cap defensively. */
    private const val MAX_MOOV_BYTES = 8 * 1024 * 1024
    /** Dolby sample entries and their config boxes, as restored by HybridCenc's frma swap. */
    private val SPATIAL_BOXES = listOf("ec-3", "dec3", "ac-4", "dac4")

    /** True when [input] is an fMP4 whose `moov` declares a FLAC track. */
    fun isMp4Flac(input: File): Boolean = readMoov(input)?.let(::findMetadataBlocks) != null

    /**
     * True when the sample entry is Dolby (E-AC-3 / AC-4). Addon flags and the
     * loopback Content-Type can't be trusted for this; the init segment can.
     */
    fun isMp4Spatial(input: File): Boolean = readMoov(input)?.let { moov ->
        SPATIAL_BOXES.any { indexOfAscii(moov, it, 0) >= 0 }
    } == true

    private fun readMoov(input: File): ByteArray? {
        if (!input.isFile || input.length() <= 0L) return null
        return runCatching {
            RandomAccessFile(input, "r").use { raf -> readLayout(raf, input.length()).moov }
        }.getOrNull()
    }

    /**
     * Writes a standalone `.flac` for [input]. Returns false (never throws) when
     * the container is not FLAC-in-MP4 or anything is malformed, so the caller
     * can keep the original download instead of losing it.
     */
    fun remux(input: File, output: File): Boolean {
        if (!input.isFile || input.length() <= 0L || input == output) return false
        if (output.exists() && output.length() > 0L) return false
        val length = input.length()
        return try {
            val layout = RandomAccessFile(input, "r").use { raf -> readLayout(raf, length) }
            val metadataBlocks = layout.moov?.let(::findMetadataBlocks) ?: return false
            if (layout.mdatRanges.isEmpty()) return false
            fillTotalSamples(metadataBlocks, input)
            output.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) return false
            }
            RandomAccessFile(input, "r").use { raf ->
                FileOutputStream(output).buffered(COPY_BUFFER).use { out ->
                    out.write(FLAC_MAGIC.toByteArray(Charsets.US_ASCII))
                    out.write(metadataBlocks)
                    for ((offset, size) in layout.mdatRanges) {
                        raf.seek(offset)
                        copyExactly(raf, out, size)
                    }
                }
            }
            if (output.length() <= 0L) {
                runCatching { output.delete() }
                return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "FLAC remux failed: ${t.message}", t)
            runCatching { output.delete() }
            false
        }
    }

    /**
     * fMP4 muxers write STREAMINFO total_samples = 0 (length unknown at init
     * time). With no SEEKTABLE, ExoPlayer then marks the .flac unseekable, so
     * fill it from the fragment durations. [blocks] starts with the 4-byte
     * STREAMINFO block header.
     */
    private fun fillTotalSamples(blocks: ByteArray, input: File) {
        if (blocks.size < 4 + STREAMINFO_LENGTH) return
        fun b(i: Int) = blocks[4 + i].toLong() and 0xFF
        val current = ((b(13) and 0x0F) shl 32) or (b(14) shl 24) or (b(15) shl 16) or (b(16) shl 8) or b(17)
        if (current != 0L) return
        val sampleRate = (b(10) shl 12) or (b(11) shl 4) or (b(12) shr 4)
        val total = Fmp4IndexBuilder.totalSamples(input, sampleRate).takeIf { it in 1 until (1L shl 36) } ?: return
        blocks[4 + 13] = ((b(13) and 0xF0) or (total shr 32)).toByte()
        for (i in 0..3) blocks[4 + 14 + i] = (total shr (24 - 8 * i)).toByte()
    }

    private class Layout(
        val moov: ByteArray?,
        val mdatRanges: List<Pair<Long, Long>>,
    )

    private fun readLayout(raf: RandomAccessFile, length: Long): Layout {
        var moovBytes: ByteArray? = null
        val mdatRanges = mutableListOf<Pair<Long, Long>>()
        val header = ByteArray(16)
        var pos = 0L
        while (pos + 8 <= length) {
            raf.seek(pos)
            if (raf.read(header, 0, 8) < 8) break
            val size32 = be32(header, 0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerSize = 8L
            var boxSize = size32.toLong() and 0xFFFF_FFFFL
            if (size32 == 1) {
                if (raf.read(header, 8, 8) < 8) break
                boxSize = be64(header, 8)
                headerSize = 16L
            } else if (size32 == 0) {
                // Box extends to the end of the file.
                boxSize = length - pos
            }
            if (boxSize < headerSize) break
            val payloadStart = pos + headerSize
            val payloadSize = boxSize - headerSize
            if (payloadSize < 0L || payloadStart + payloadSize > length) break
            when (type) {
                "moov" -> if (moovBytes == null && payloadSize in 1..MAX_MOOV_BYTES.toLong()) {
                    moovBytes = ByteArray(payloadSize.toInt()).also {
                        raf.seek(payloadStart)
                        raf.readFully(it)
                    }
                }
                "mdat" -> if (payloadSize > 0L) mdatRanges += payloadStart to payloadSize
            }
            pos += boxSize
        }
        return Layout(moovBytes, mdatRanges)
    }

    /**
     * Locates `dfLa` inside `moov` and returns its FLAC metadata block chain.
     * The box is a FullBox, so the blocks may start after a 4-byte
     * version/flags field; both offsets are tried and the candidate that parses
     * as a valid chain wins.
     */
    private fun findMetadataBlocks(moov: ByteArray): ByteArray? {
        var from = 0
        while (true) {
            val tagIndex = indexOfAscii(moov, "dfLa", from)
            if (tagIndex < 0) return null
            if (tagIndex >= 4) {
                val boxStart = tagIndex - 4
                val declared = be32(moov, boxStart)
                val boxEnd = if (declared >= 12 && boxStart + declared <= moov.size) {
                    boxStart + declared
                } else {
                    moov.size
                }
                val payloadStart = tagIndex + 4
                if (payloadStart < boxEnd) {
                    val payload = moov.copyOfRange(payloadStart, boxEnd)
                    parseMetadataChain(payload, 0)?.let { return it }
                    parseMetadataChain(payload, 4)?.let { return it }
                }
            }
            from = tagIndex + 4
        }
    }

    /**
     * Validates a FLAC metadata block chain starting at [start] and returns the
     * blocks with the LAST-block flag forced onto the final one (a native FLAC
     * stream requires it; the container may not have set it).
     */
    private fun parseMetadataChain(payload: ByteArray, start: Int): ByteArray? {
        if (start < 0 || payload.size - start < 4) return null
        var cursor = start
        var first = true
        var lastBlockStart = -1
        while (cursor + 4 <= payload.size) {
            val firstByte = payload[cursor].toInt() and 0xFF
            val type = firstByte and 0x7F
            val blockLength = ((payload[cursor + 1].toInt() and 0xFF) shl 16) or
                ((payload[cursor + 2].toInt() and 0xFF) shl 8) or
                (payload[cursor + 3].toInt() and 0xFF)
            // The first block must be STREAMINFO with its fixed 34-byte payload.
            if (first && (type != STREAMINFO_TYPE || blockLength != STREAMINFO_LENGTH)) return null
            if (cursor + 4 + blockLength > payload.size) return null
            lastBlockStart = cursor
            first = false
            cursor += 4 + blockLength
            if (firstByte and LAST_METADATA_BLOCK_FLAG != 0) break
        }
        if (first || lastBlockStart < 0) return null
        val end = cursor.coerceAtMost(payload.size)
        if (end <= start) return null
        val blocks = payload.copyOfRange(start, end)
        val relativeLast = lastBlockStart - start
        if (relativeLast in blocks.indices) {
            blocks[relativeLast] = (blocks[relativeLast].toInt() or LAST_METADATA_BLOCK_FLAG).toByte()
        }
        return blocks
    }

    private fun copyExactly(raf: RandomAccessFile, out: OutputStream, size: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = size
        while (remaining > 0L) {
            val chunk = minOf(buffer.size.toLong(), remaining).toInt()
            val read = raf.read(buffer, 0, chunk)
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun indexOfAscii(haystack: ByteArray, needle: String, from: Int): Int {
        val target = needle.toByteArray(Charsets.US_ASCII)
        if (from < 0 || target.isEmpty()) return -1
        var i = from
        while (i + target.size <= haystack.size) {
            var matched = true
            for (j in target.indices) {
                if (haystack[i + j] != target[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
            i++
        }
        return -1
    }

    private fun be32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun be64(bytes: ByteArray, offset: Int): Long {
        if (offset + 8 > bytes.size) return 0L
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
