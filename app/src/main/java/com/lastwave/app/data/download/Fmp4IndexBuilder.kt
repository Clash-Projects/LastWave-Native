package com.lastwave.app.data.download

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Builds Segment Index (sidx) and Movie Fragment Random Access (mfra/tfra/mfro)
 * indexing, and patches duration headers for fragmented MP4 (fMP4) audio files
 * (such as Dolby Atmos E-AC-3 and AAC DASH downloads).
 *
 * DASH segments concatenate `0.mp4` (init chunk with duration=0) and media chunks
 * (`moof` + `mdat`) without an inline seek index. ExoPlayer's FragmentedMp4Extractor
 * marks unindexed fMP4 as unseekable (SeekMap.Unseekable) and external players
 * display 0:00 duration or fail to seek.
 *
 * This builder:
 * 1. Scans `moof` + `mdat` fragments and reads `tfdt` decode times and subsegment sizes.
 * 2. Patches `duration` in `mvhd`, `tkhd`, and `mdhd` headers so players immediately
 *    know the true duration.
 * 3. Inserts a standard `sidx` box (ISO/IEC 14496-12) between `moov` and the first `moof`
 *    so Media3 / ExoPlayer generates a ChunkIndex seek map for instant seeking.
 * 4. Appends a terminal `mfra` box (`tfra` + `mfro`) so all external players and
 *    tools can seek with sample-accurate random access.
 */
object Fmp4IndexBuilder {
    private const val TAG = "Fmp4IndexBuilder"
    private const val MFRO_BOX_SIZE = 16
    private const val COPY_BUFFER_SIZE = 64 * 1024

    data class FragmentInfo(
        val time: Long,
        val duration: Long,
        val moofOffset: Long,
        val totalSize: Long, // moof size + mdat size
    )

    data class MoovInfo(
        var movieTimescale: Long = 1000L,
        var movieDuration: Long = 0L,
        var movieDurationOffset: Long = -1L,
        var movieDurationIs64: Boolean = false,
        var trackId: Long = 1L,
        var trackDuration: Long = 0L,
        var trackDurationOffset: Long = -1L,
        var trackDurationIs64: Boolean = false,
        var mediaTimescale: Long = 48000L,
        var mediaDuration: Long = 0L,
        var mediaDurationOffset: Long = -1L,
        var mediaDurationIs64: Boolean = false,
    )

    /**
     * Checks if the file is a fragmented MP4 (contains at least one top-level `moof` box).
     */
    fun isFragmentedMp4(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 32) return false
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var pos = 0L
            val header = ByteArray(16)
            while (pos + 8 <= length) {
                raf.seek(pos)
                if (raf.read(header, 0, 8) < 8) break
                val size32 = be32(header, 0)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                var boxSize = size32.toLong() and 0xFFFF_FFFFL
                var headerSize = 8L
                if (size32 == 1) {
                    if (raf.read(header, 8, 8) < 8) break
                    boxSize = be64(header, 8)
                    headerSize = 16L
                } else if (size32 == 0) {
                    boxSize = length - pos
                }
                if (boxSize < headerSize) break
                if (type == "moof") return true
                pos += boxSize
            }
            false
        }
    }.getOrDefault(false)

    /**
     * Checks if the file already contains an `mfra` box at the end.
     */
    fun hasMfraBox(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < MFRO_BOX_SIZE) return false
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            raf.seek(length - MFRO_BOX_SIZE)
            val footer = ByteArray(MFRO_BOX_SIZE)
            raf.readFully(footer)
            val mfroType = String(footer, 4, 4, Charsets.US_ASCII)
            if (mfroType == "mfro") {
                val mfraSize = be32(footer, 12).toLong() and 0xFFFF_FFFFL
                if (mfraSize >= MFRO_BOX_SIZE && mfraSize <= length) {
                    raf.seek(length - mfraSize + 4)
                    val mfraTypeBytes = ByteArray(4)
                    raf.readFully(mfraTypeBytes)
                    return String(mfraTypeBytes, Charsets.US_ASCII) == "mfra"
                }
            }
            false
        }
    }.getOrDefault(false)

    /**
     * Finalizes a fragmented MP4 file:
     * - Patches `mvhd`, `tkhd`, `mdhd` duration headers if zero.
     * - Inserts `sidx` box between `moov` and the first `moof` if missing.
     * - Appends `mfra` box (`tfra` + `mfro`) if missing.
     *
     * Returns true if indexing/patching succeeded or file was already indexed.
     */
    fun finalizeFmp4(file: File, knownDurationMs: Long = 0L): Boolean = runCatching {
        if (!file.isFile || file.length() < 32) return false

        val moovInfo = MoovInfo()
        val fragments = mutableListOf<FragmentInfo>()
        var firstMoofOffset = -1L
        var hasSidx = false
        var currentMoofOffset = -1L
        var currentMoofSize = 0L
        var currentFragTime = 0L
        var currentFragDuration = 0L
        var lastFragEnd = 0L

        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var pos = 0L
            val header = ByteArray(16)

            while (pos + 8 <= length) {
                raf.seek(pos)
                if (raf.read(header, 0, 8) < 8) break
                val size32 = be32(header, 0)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                var boxSize = size32.toLong() and 0xFFFF_FFFFL
                var headerSize = 8L
                if (size32 == 1) {
                    if (raf.read(header, 8, 8) < 8) break
                    boxSize = be64(header, 8)
                    headerSize = 16L
                } else if (size32 == 0) {
                    boxSize = length - pos
                }
                if (boxSize < headerSize) break

                val payloadStart = pos + headerSize
                val payloadSize = boxSize - headerSize

                when (type) {
                    "moov" -> {
                        if (payloadSize in 1..(32 * 1024 * 1024)) {
                            parseMoovBox(raf, payloadStart, payloadSize, moovInfo)
                        }
                    }
                    "sidx" -> {
                        hasSidx = true
                    }
                    "moof" -> {
                        if (firstMoofOffset < 0L) {
                            firstMoofOffset = pos
                        }
                        currentMoofOffset = pos
                        currentMoofSize = boxSize
                        val (fragTime, fragDuration) = parseMoofBox(
                            raf = raf,
                            payloadStart = payloadStart,
                            payloadSize = payloadSize,
                            expectedTrackId = moovInfo.trackId,
                            fallbackTime = lastFragEnd,
                        )
                        currentFragTime = fragTime
                        currentFragDuration = fragDuration
                        lastFragEnd = fragTime + fragDuration
                    }
                    "mdat" -> {
                        if (currentMoofOffset >= 0L) {
                            val totalSubsegmentSize = currentMoofSize + boxSize
                            fragments.add(
                                FragmentInfo(
                                    time = currentFragTime,
                                    duration = currentFragDuration,
                                    moofOffset = currentMoofOffset,
                                    totalSize = totalSubsegmentSize,
                                )
                            )
                            currentMoofOffset = -1L
                        }
                    }
                }

                pos += boxSize
            }
        }

        if (firstMoofOffset < 0L || fragments.isEmpty()) {
            // Progressive MP4: patch duration if zero and known
            if (knownDurationMs > 0L) {
                RandomAccessFile(file, "rw").use { raf ->
                    patchDurationsIfZero(raf, moovInfo, knownDurationMs)
                }
            }
            return true
        }

        // Calculate total duration in media timescale
        val totalMediaDuration = if (lastFragEnd > 0L) {
            lastFragEnd
        } else if (knownDurationMs > 0L && moovInfo.mediaTimescale > 0L) {
            (knownDurationMs * moovInfo.mediaTimescale) / 1000L
        } else {
            0L
        }

        // 1. Insert sidx box before the first moof if not already present
        var finalFile = file
        var sidxInsertedSize = 0L

        if (!hasSidx && fragments.size <= 65535) {
            val sidxBytes = buildSidxBox(
                trackId = moovInfo.trackId,
                timescale = moovInfo.mediaTimescale,
                earliestPresentationTime = fragments.first().time,
                fragments = fragments,
            )
            val tempFile = File.createTempFile("fmp4_sidx_", ".tmp", file.parentFile ?: file.canonicalFile.parentFile)
            var insertSuccess = false
            try {
                FileOutputStream(tempFile).buffered(COPY_BUFFER_SIZE).use { out ->
                    FileInputStream(file).use { input ->
                        copyExactly(input, out, firstMoofOffset)
                        out.write(sidxBytes)
                        copyStream(input, out)
                    }
                    out.flush()
                }
                if (tempFile.length() == file.length() + sidxBytes.size) {
                    if (file.delete() && tempFile.renameTo(file)) {
                        insertSuccess = true
                        sidxInsertedSize = sidxBytes.size.toLong()
                        Log.i(TAG, "Inserted sidx box (${sidxBytes.size} bytes) before first moof in ${file.name}")
                    }
                }
            } catch (err: Exception) {
                Log.w(TAG, "Could not insert sidx box for ${file.name}: ${err.message}", err)
            } finally {
                if (!insertSuccess && tempFile.exists()) {
                    runCatching { tempFile.delete() }
                }
            }
        }

        // 2. Patch duration in mvhd, tkhd, mdhd and append mfra
        RandomAccessFile(file, "rw").use { raf ->
            patchDurations(raf, moovInfo, totalMediaDuration, knownDurationMs)

            val currentLength = raf.length()
            val alreadyHasMfra = if (currentLength >= MFRO_BOX_SIZE) {
                raf.seek(currentLength - MFRO_BOX_SIZE)
                val footer = ByteArray(MFRO_BOX_SIZE)
                raf.readFully(footer)
                String(footer, 4, 4, Charsets.US_ASCII) == "mfro"
            } else false

            if (!alreadyHasMfra) {
                val shiftedFragments = if (sidxInsertedSize > 0L) {
                    fragments.map { it.copy(moofOffset = it.moofOffset + sidxInsertedSize) }
                } else {
                    fragments
                }
                val mfraBytes = buildMfraBox(moovInfo.trackId, shiftedFragments)
                raf.seek(currentLength)
                raf.write(mfraBytes)
                Log.i(TAG, "Appended mfra index with ${fragments.size} fragments (${mfraBytes.size} bytes) to ${file.name}")
            }
        }

        true
    }.getOrElse { error ->
        Log.w(TAG, "Failed to finalize fMP4 for ${file.name}: ${error.message}", error)
        false
    }

    private fun parseMoovBox(
        raf: RandomAccessFile,
        moovStart: Long,
        moovSize: Long,
        moovInfo: MoovInfo,
    ) {
        val moovBytes = ByteArray(moovSize.toInt())
        raf.seek(moovStart)
        raf.readFully(moovBytes)

        var pos = 0
        while (pos + 8 <= moovBytes.size) {
            val size = be32(moovBytes, pos)
            val type = String(moovBytes, pos + 4, 4, Charsets.US_ASCII)
            val boxSize = size.toLong() and 0xFFFF_FFFFL
            if (boxSize < 8 || pos + boxSize > moovBytes.size) break

            val payloadStart = pos + 8
            val payloadSize = (boxSize - 8).toInt()

            when (type) {
                "mvhd" -> {
                    val version = moovBytes[payloadStart].toInt() and 0xFF
                    if (version == 1 && payloadSize >= 28) {
                        moovInfo.movieTimescale = be32(moovBytes, payloadStart + 16).toLong() and 0xFFFF_FFFFL
                        moovInfo.movieDuration = be64(moovBytes, payloadStart + 20)
                        moovInfo.movieDurationOffset = moovStart + payloadStart + 20
                        moovInfo.movieDurationIs64 = true
                    } else if (version == 0 && payloadSize >= 20) {
                        moovInfo.movieTimescale = be32(moovBytes, payloadStart + 8).toLong() and 0xFFFF_FFFFL
                        moovInfo.movieDuration = be32(moovBytes, payloadStart + 12).toLong() and 0xFFFF_FFFFL
                        moovInfo.movieDurationOffset = moovStart + payloadStart + 12
                        moovInfo.movieDurationIs64 = false
                    }
                }
                "trak" -> {
                    parseTrakBox(moovBytes, payloadStart, payloadSize, moovStart, moovInfo)
                }
            }

            pos += boxSize.toInt()
        }
    }

    private fun parseTrakBox(
        bytes: ByteArray,
        trakStart: Int,
        trakSize: Int,
        fileMoovStart: Long,
        moovInfo: MoovInfo,
    ) {
        var pos = trakStart
        val end = trakStart + trakSize
        while (pos + 8 <= end) {
            val size = be32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val boxSize = size.toLong() and 0xFFFF_FFFFL
            if (boxSize < 8 || pos + boxSize > end) break

            val payloadStart = pos + 8
            val payloadSize = (boxSize - 8).toInt()

            when (type) {
                "tkhd" -> {
                    val version = bytes[payloadStart].toInt() and 0xFF
                    if (version == 1 && payloadSize >= 32) {
                        moovInfo.trackId = be32(bytes, payloadStart + 16).toLong() and 0xFFFF_FFFFL
                        moovInfo.trackDuration = be64(bytes, payloadStart + 24)
                        moovInfo.trackDurationOffset = fileMoovStart + payloadStart + 24
                        moovInfo.trackDurationIs64 = true
                    } else if (version == 0 && payloadSize >= 24) {
                        moovInfo.trackId = be32(bytes, payloadStart + 8).toLong() and 0xFFFF_FFFFL
                        moovInfo.trackDuration = be32(bytes, payloadStart + 16).toLong() and 0xFFFF_FFFFL
                        moovInfo.trackDurationOffset = fileMoovStart + payloadStart + 16
                        moovInfo.trackDurationIs64 = false
                    }
                }
                "mdia" -> {
                    parseMdiaBox(bytes, payloadStart, payloadSize, fileMoovStart, moovInfo)
                }
            }

            pos += boxSize.toInt()
        }
    }

    private fun parseMdiaBox(
        bytes: ByteArray,
        mdiaStart: Int,
        mdiaSize: Int,
        fileMoovStart: Long,
        moovInfo: MoovInfo,
    ) {
        var pos = mdiaStart
        val end = mdiaStart + mdiaSize
        while (pos + 8 <= end) {
            val size = be32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val boxSize = size.toLong() and 0xFFFF_FFFFL
            if (boxSize < 8 || pos + boxSize > end) break

            val payloadStart = pos + 8
            val payloadSize = (boxSize - 8).toInt()

            if (type == "mdhd") {
                val version = bytes[payloadStart].toInt() and 0xFF
                if (version == 1 && payloadSize >= 28) {
                    moovInfo.mediaTimescale = be32(bytes, payloadStart + 16).toLong() and 0xFFFF_FFFFL
                    moovInfo.mediaDuration = be64(bytes, payloadStart + 20)
                    moovInfo.mediaDurationOffset = fileMoovStart + payloadStart + 20
                    moovInfo.mediaDurationIs64 = true
                } else if (version == 0 && payloadSize >= 20) {
                    moovInfo.mediaTimescale = be32(bytes, payloadStart + 8).toLong() and 0xFFFF_FFFFL
                    moovInfo.mediaDuration = be32(bytes, payloadStart + 12).toLong() and 0xFFFF_FFFFL
                    moovInfo.mediaDurationOffset = fileMoovStart + payloadStart + 12
                    moovInfo.mediaDurationIs64 = false
                }
            }

            pos += boxSize.toInt()
        }
    }

    private fun parseMoofBox(
        raf: RandomAccessFile,
        payloadStart: Long,
        payloadSize: Long,
        expectedTrackId: Long,
        fallbackTime: Long,
    ): Pair<Long, Long> {
        val moofBytes = ByteArray(minOf(payloadSize.toInt(), 512 * 1024))
        raf.seek(payloadStart)
        raf.readFully(moofBytes)

        var fragTime = fallbackTime
        var fragDuration = 0L

        var pos = 0
        while (pos + 8 <= moofBytes.size) {
            val size = be32(moofBytes, pos)
            val type = String(moofBytes, pos + 4, 4, Charsets.US_ASCII)
            val boxSize = size.toLong() and 0xFFFF_FFFFL
            if (boxSize < 8 || pos + boxSize > moofBytes.size) break

            if (type == "traf") {
                var trafPos = pos + 8
                val trafEnd = pos + boxSize.toInt()
                var defaultSampleDuration = 0L

                while (trafPos + 8 <= trafEnd) {
                    val subSize = be32(moofBytes, trafPos)
                    val subType = String(moofBytes, trafPos + 4, 4, Charsets.US_ASCII)
                    val subBoxSize = subSize.toLong() and 0xFFFF_FFFFL
                    if (subBoxSize < 8 || trafPos + subBoxSize > trafEnd) break

                    val subPayload = trafPos + 8
                    val subPayloadSize = (subBoxSize - 8).toInt()

                    when (subType) {
                        "tfhd" -> {
                            if (subPayloadSize >= 8) {
                                val flags = (moofBytes[subPayload + 1].toInt() and 0xFF shl 16) or
                                    (moofBytes[subPayload + 2].toInt() and 0xFF shl 8) or
                                    (moofBytes[subPayload + 3].toInt() and 0xFF)
                                var cursor = subPayload + 8
                                if ((flags and 0x000001) != 0 && cursor + 8 <= trafEnd) cursor += 8
                                if ((flags and 0x000002) != 0 && cursor + 4 <= trafEnd) cursor += 4
                                if ((flags and 0x000008) != 0 && cursor + 4 <= trafEnd) {
                                    defaultSampleDuration = be32(moofBytes, cursor).toLong() and 0xFFFF_FFFFL
                                }
                            }
                        }
                        "tfdt" -> {
                            if (subPayloadSize >= 4) {
                                val version = moofBytes[subPayload].toInt() and 0xFF
                                if (version == 1 && subPayloadSize >= 12) {
                                    fragTime = be64(moofBytes, subPayload + 4)
                                } else if (version == 0 && subPayloadSize >= 8) {
                                    fragTime = be32(moofBytes, subPayload + 4).toLong() and 0xFFFF_FFFFL
                                }
                            }
                        }
                        "trun" -> {
                            if (subPayloadSize >= 8) {
                                val flags = (moofBytes[subPayload + 1].toInt() and 0xFF shl 16) or
                                    (moofBytes[subPayload + 2].toInt() and 0xFF shl 8) or
                                    (moofBytes[subPayload + 3].toInt() and 0xFF)
                                val sampleCount = be32(moofBytes, subPayload + 4)
                                if ((flags and 0x000100) != 0) {
                                    var cursor = subPayload + 8
                                    if ((flags and 0x000001) != 0) cursor += 4
                                    if ((flags and 0x000004) != 0) cursor += 4
                                    var totalDuration = 0L
                                    val entrySize = 4 +
                                        (if ((flags and 0x000200) != 0) 4 else 0) +
                                        (if ((flags and 0x000400) != 0) 4 else 0) +
                                        (if ((flags and 0x000800) != 0) 4 else 0)
                                    for (i in 0 until minOf(sampleCount, 4096)) {
                                        if (cursor + 4 <= trafEnd) {
                                            totalDuration += be32(moofBytes, cursor).toLong() and 0xFFFF_FFFFL
                                            cursor += entrySize
                                        } else break
                                    }
                                    if (totalDuration > 0L) fragDuration = totalDuration
                                } else if (defaultSampleDuration > 0L && sampleCount > 0) {
                                    fragDuration = defaultSampleDuration * sampleCount
                                }
                            }
                        }
                    }

                    trafPos += subBoxSize.toInt()
                }
            }

            pos += boxSize.toInt()
        }

        return Pair(fragTime, fragDuration)
    }

    private fun patchDurationsIfZero(raf: RandomAccessFile, info: MoovInfo, knownDurationMs: Long) {
        if (knownDurationMs <= 0L) return
        val movieScale = info.movieTimescale.takeIf { it > 0 } ?: 1000L
        val movieDuration = (knownDurationMs * movieScale) / 1000L
        val mediaScale = info.mediaTimescale.takeIf { it > 0 } ?: 48000L
        val mediaDuration = (knownDurationMs * mediaScale) / 1000L

        if (info.movieDuration == 0L && info.movieDurationOffset > 0) {
            raf.seek(info.movieDurationOffset)
            if (info.movieDurationIs64) writeBe64(raf, movieDuration)
            else writeBe32(raf, movieDuration.toInt())
        }
        if (info.trackDuration == 0L && info.trackDurationOffset > 0) {
            raf.seek(info.trackDurationOffset)
            if (info.trackDurationIs64) writeBe64(raf, movieDuration)
            else writeBe32(raf, movieDuration.toInt())
        }
        if (info.mediaDuration == 0L && info.mediaDurationOffset > 0) {
            raf.seek(info.mediaDurationOffset)
            if (info.mediaDurationIs64) writeBe64(raf, mediaDuration)
            else writeBe32(raf, mediaDuration.toInt())
        }
    }

    private fun patchDurations(
        raf: RandomAccessFile,
        info: MoovInfo,
        totalMediaDuration: Long,
        knownDurationMs: Long,
    ) {
        val mediaScale = info.mediaTimescale.takeIf { it > 0 } ?: 48000L
        val movieScale = info.movieTimescale.takeIf { it > 0 } ?: 1000L

        val effectiveMediaDur = if (totalMediaDuration > 0L) {
            totalMediaDuration
        } else if (knownDurationMs > 0L) {
            (knownDurationMs * mediaScale) / 1000L
        } else 0L

        if (effectiveMediaDur <= 0L) return

        val movieDuration = (effectiveMediaDur * movieScale) / mediaScale

        if (info.movieDurationOffset > 0) {
            raf.seek(info.movieDurationOffset)
            if (info.movieDurationIs64) writeBe64(raf, movieDuration)
            else writeBe32(raf, movieDuration.toInt())
        }
        if (info.trackDurationOffset > 0) {
            raf.seek(info.trackDurationOffset)
            if (info.trackDurationIs64) writeBe64(raf, movieDuration)
            else writeBe32(raf, movieDuration.toInt())
        }
        if (info.mediaDurationOffset > 0) {
            raf.seek(info.mediaDurationOffset)
            if (info.mediaDurationIs64) writeBe64(raf, effectiveMediaDur)
            else writeBe32(raf, effectiveMediaDur.toInt())
        }
    }

    /**
     * Builds an ISO/IEC 14496-12 compliant `sidx` (Segment Index) box.
     * When parsed by Media3's FragmentedMp4Extractor.parseSidx(), this produces
     * a sample-accurate ChunkIndex seek map.
     */
    fun buildSidxBox(
        trackId: Long,
        timescale: Long,
        earliestPresentationTime: Long,
        fragments: List<FragmentInfo>,
    ): ByteArray {
        val referenceCount = fragments.size
        val totalBoxSize = 32 + referenceCount * 12
        val out = ByteArrayOutputStream(totalBoxSize)

        // Box header
        writeBe32(out, totalBoxSize)
        out.write("sidx".toByteArray(Charsets.US_ASCII))

        // FullBox: version=0 (1 byte), flags=0 (3 bytes)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)

        // reference_ID (4 bytes)
        writeBe32(out, trackId.toInt())

        // timescale (4 bytes)
        writeBe32(out, (timescale.takeIf { it > 0 } ?: 48000L).toInt())

        // earliest_presentation_time (4 bytes, v0)
        writeBe32(out, (earliestPresentationTime and 0xFFFF_FFFFL).toInt())

        // first_offset (4 bytes, v0): distance from the end of this sidx box to the first moof = 0
        writeBe32(out, 0)

        // reserved (2 bytes: 0)
        out.write(0)
        out.write(0)

        // reference_count (2 bytes)
        out.write((referenceCount ushr 8) and 0xFF)
        out.write(referenceCount and 0xFF)

        // References: 12 bytes each
        for (frag in fragments) {
            // reference_type (1 bit = 0) + referenced_size (31 bits)
            val refSize = (frag.totalSize and 0x7FFFFFFF).toInt()
            writeBe32(out, refSize)

            // subsegment_duration (4 bytes)
            val dur = (frag.duration and 0xFFFF_FFFFL).toInt()
            writeBe32(out, dur)

            // starts_with_SAP(1 bit = 1) | SAP_type(3 bits = 1) | SAP_delta_time(28 bits = 0) = 0x90000000
            writeBe32(out, 0x90000000.toInt())
        }

        return out.toByteArray()
    }

    /**
     * Builds an ISO/IEC 14496-12 compliant `mfra` container box consisting of
     * a version 1 `tfra` box (with 64-bit time and 64-bit moof byte offsets)
     * and a terminal `mfro` box.
     */
    fun buildMfraBox(trackId: Long, entries: List<FragmentInfo>): ByteArray {
        val tfraStream = ByteArrayOutputStream()
        tfraStream.write(1) // version 1
        tfraStream.write(0)
        tfraStream.write(0)
        tfraStream.write(0)
        writeBe32(tfraStream, trackId.toInt())
        writeBe32(tfraStream, 0)
        writeBe32(tfraStream, entries.size)
        for (entry in entries) {
            writeBe64(tfraStream, entry.time)
            writeBe64(tfraStream, entry.moofOffset)
            tfraStream.write(1)
            tfraStream.write(1)
            tfraStream.write(1)
        }
        val tfraPayload = tfraStream.toByteArray()
        val tfraBoxSize = 8 + tfraPayload.size

        val mfroBoxSize = MFRO_BOX_SIZE
        val totalMfraSize = 8 + tfraBoxSize + mfroBoxSize

        val out = ByteArrayOutputStream(totalMfraSize)
        writeBe32(out, totalMfraSize)
        out.write("mfra".toByteArray(Charsets.US_ASCII))

        writeBe32(out, tfraBoxSize)
        out.write("tfra".toByteArray(Charsets.US_ASCII))
        out.write(tfraPayload)

        writeBe32(out, mfroBoxSize)
        out.write("mfro".toByteArray(Charsets.US_ASCII))
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        writeBe32(out, totalMfraSize)

        return out.toByteArray()
    }

    private fun copyExactly(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (remaining > 0L) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
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

    private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeBe64(out: ByteArrayOutputStream, value: Long) {
        for (i in 7 downTo 0) {
            out.write(((value ushr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeBe32(raf: RandomAccessFile, value: Int) {
        raf.writeByte((value ushr 24) and 0xFF)
        raf.writeByte((value ushr 16) and 0xFF)
        raf.writeByte((value ushr 8) and 0xFF)
        raf.writeByte(value and 0xFF)
    }

    private fun writeBe64(raf: RandomAccessFile, value: Long) {
        for (i in 7 downTo 0) {
            raf.writeByte(((value ushr (i * 8)) and 0xFF).toInt())
        }
    }
}
