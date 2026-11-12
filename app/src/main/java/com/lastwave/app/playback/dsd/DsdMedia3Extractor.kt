package com.lastwave.app.playback.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.lastwave.app.playback.AudioHeaderInspector
import kotlin.math.min

/**
 * Media3 [Extractor] for 1-bit Direct Stream Digital files:
 * - **DSF** (`"DSD "` planar block container, LSB/MSB bit order)
 * - **DFF / DSDIFF** (`"FRM8"` + `"DSD "` uncompressed interleaved MSB container)
 *
 * Streams raw DSD blocks through [DsdNativeDecimator] (C++ Kaiser FIR decimator)
 * and publishes standard `MimeTypes.AUDIO_RAW` (`C.ENCODING_PCM_FLOAT`) frames at
 * 176.4 kHz or 192 kHz so playback flows natively through Oboe, EQ, Automix,
 * ReplayGain, and USB DAC bit-perfect routing.
 */
@UnstableApi
class DsdMedia3Extractor : Extractor {

    private enum class ContainerKind {
        DSF,
        DFF,
    }

    private data class DsdStreamSpec(
        val kind: ContainerKind,
        val channels: Int,
        val dsdSampleRateHz: Int,
        val lsbFirst: Boolean,
        val blockSizePerChannel: Int,
        val dataStart: Long,
        val totalByteFrames: Long,
        val totalBlockBytes: Long,
    )

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var state = STATE_HEADER
    private var spec: DsdStreamSpec? = null
    private var decimator: DsdNativeDecimator? = null

    private var currentByteFrame = 0L
    private var pendingSeekByteFrame = 0L
    private var inputBuffer = ByteArray(16 * 1024)
    private var outputBuffer = ByteArray(64 * 1024)
    private val parsableOut = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(16)
        if (!input.peekFully(header, 0, 16, true)) return false
        val id0 = String(header, 0, 4, Charsets.US_ASCII)
        if (id0 == "DSD ") return true
        if (id0 == "FRM8") {
            return String(header, 12, 4, Charsets.US_ASCII) == "DSD "
        }
        return false
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun seek(position: Long, timeUs: Long) {
        val s = spec ?: run {
            state = STATE_HEADER
            return
        }
        decimator?.reset()
        val byteFramesPerSec = s.dsdSampleRateHz / 8L
        val targetFrame = ((timeUs * byteFramesPerSec) / 1_000_000L).coerceIn(0L, s.totalByteFrames)
        pendingSeekByteFrame = if (s.kind == ContainerKind.DSF) {
            (targetFrame / s.blockSizePerChannel) * s.blockSizePerChannel
        } else {
            targetFrame
        }
        currentByteFrame = pendingSeekByteFrame
        val targetPos = byteOffsetForFrame(s, pendingSeekByteFrame)
        state = if (position == targetPos) STATE_READING else STATE_SEEK_TO_DATA
    }

    override fun release() {
        decimator?.close()
        decimator = null
        extractorOutput = null
        trackOutput = null
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val out = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        if (state == STATE_HEADER) {
            parseHeader(input)
            if (state == STATE_SEEK_TO_DATA) {
                val s = spec!!
                seekPosition.position = byteOffsetForFrame(s, pendingSeekByteFrame)
                state = STATE_READING
                return Extractor.RESULT_SEEK
            }
        }
        if (state == STATE_SEEK_TO_DATA) {
            val s = spec ?: return Extractor.RESULT_END_OF_INPUT
            val targetPos = byteOffsetForFrame(s, pendingSeekByteFrame)
            if (input.position != targetPos) {
                seekPosition.position = targetPos
                state = STATE_READING
                return Extractor.RESULT_SEEK
            }
            state = STATE_READING
        }

        val s = spec ?: return Extractor.RESULT_END_OF_INPUT
        val dec = decimator ?: return Extractor.RESULT_END_OF_INPUT
        if (!dec.isReady) return Extractor.RESULT_END_OF_INPUT
        if (currentByteFrame >= s.totalByteFrames) return Extractor.RESULT_END_OF_INPUT

        return when (s.kind) {
            ContainerKind.DSF -> readDsfBlock(input, out, s, dec)
            ContainerKind.DFF -> readDffChunk(input, out, s, dec)
        }
    }

    private fun readDsfBlock(
        input: ExtractorInput,
        out: TrackOutput,
        s: DsdStreamSpec,
        dec: DsdNativeDecimator,
    ): Int {
        val fullBlockBytes = s.channels * s.blockSizePerChannel
        if (inputBuffer.size < fullBlockBytes) inputBuffer = ByteArray(fullBlockBytes)
        if (!input.readFully(inputBuffer, 0, fullBlockBytes, true)) {
            return Extractor.RESULT_END_OF_INPUT
        }

        val remainingFrames = (s.totalByteFrames - currentByteFrame).toInt()
        val validFramesInBlock = min(s.blockSizePerChannel, remainingFrames)
        if (validFramesInBlock <= 0) return Extractor.RESULT_END_OF_INPUT

        val neededOut = dec.maxOutputBytes(fullBlockBytes, planar = true, blockSizePerChannel = validFramesInBlock)
        if (outputBuffer.size < neededOut) outputBuffer = ByteArray(neededOut)

        // When final block has trailing padding, still index each channel plane by s.blockSizePerChannel
        // and pass validFramesInBlock as active length? Native processPlanar expects expectedBlock == blockSize*channels;
        // feed whole block or slice valid prefix tightly if truncated at EOF.
        val bytesToDecode = if (validFramesInBlock == s.blockSizePerChannel) {
            inputBuffer
        } else {
            val trimmed = ByteArray(s.channels * validFramesInBlock)
            for (ch in 0 until s.channels) {
                System.arraycopy(
                    inputBuffer,
                    ch * s.blockSizePerChannel,
                    trimmed,
                    ch * validFramesInBlock,
                    validFramesInBlock,
                )
            }
            trimmed
        }

        val outBytes = dec.process(
            input = bytesToDecode,
            offset = 0,
            length = s.channels * validFramesInBlock,
            planar = true,
            blockSizePerChannel = validFramesInBlock,
            outputPcmBytes = outputBuffer,
        )
        val timeUs = (currentByteFrame * 8L * 1_000_000L) / s.dsdSampleRateHz
        currentByteFrame += validFramesInBlock.toLong()

        if (outBytes > 0) {
            parsableOut.reset(outputBuffer, outBytes)
            out.sampleData(parsableOut, outBytes)
            out.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, outBytes, 0, null)
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun readDffChunk(
        input: ExtractorInput,
        out: TrackOutput,
        s: DsdStreamSpec,
        dec: DsdNativeDecimator,
    ): Int {
        val remainingFrames = s.totalByteFrames - currentByteFrame
        val maxReadFrames = min(remainingFrames, (4096L).coerceAtMost((inputBuffer.size / s.channels).toLong())).toInt()
        if (maxReadFrames <= 0) return Extractor.RESULT_END_OF_INPUT
        val byteLen = maxReadFrames * s.channels
        val read = input.read(inputBuffer, 0, byteLen)
        if (read == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT

        val alignedBytes = if (read % s.channels == 0) {
            read
        } else {
            val need = s.channels - (read % s.channels)
            if (input.readFully(inputBuffer, read, need, true)) read + need
            else (read / s.channels) * s.channels
        }
        if (alignedBytes <= 0) return Extractor.RESULT_END_OF_INPUT

        val framesRead = alignedBytes / s.channels
        val neededOut = dec.maxOutputBytes(alignedBytes, planar = false, blockSizePerChannel = 0)
        if (outputBuffer.size < neededOut) outputBuffer = ByteArray(neededOut)

        val outBytes = dec.process(
            input = inputBuffer,
            offset = 0,
            length = alignedBytes,
            planar = false,
            blockSizePerChannel = 0,
            outputPcmBytes = outputBuffer,
        )
        val timeUs = (currentByteFrame * 8L * 1_000_000L) / s.dsdSampleRateHz
        currentByteFrame += framesRead.toLong()

        if (outBytes > 0) {
            parsableOut.reset(outputBuffer, outBytes)
            out.sampleData(parsableOut, outBytes)
            out.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, outBytes, 0, null)
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun parseHeader(input: ExtractorInput) {
        val magic = ByteArray(16)
        input.readFully(magic, 0, 16)
        val headStr = String(magic, 0, 4, Charsets.US_ASCII)
        val parsed = when {
            headStr == "DSD " -> parseDsfStream(input, magic)
            headStr == "FRM8" && String(magic, 12, 4, Charsets.US_ASCII) == "DSD " ->
                parseDffStream(input)
            else -> throw ParserException.createForMalformedContainer("Unsupported DSD container", null)
        }

        val dec = DsdNativeDecimator(
            channelCount = parsed.channels,
            dsdSampleRateHz = parsed.dsdSampleRateHz,
            lsbFirst = parsed.lsbFirst,
        )
        if (!dec.isReady) {
            dec.close()
            throw ParserException.createForUnsupportedContainerFeature("Native DSD decimator unavailable")
        }

        spec = parsed
        decimator = dec
        currentByteFrame = 0L
        pendingSeekByteFrame = 0L

        val durationUs = (parsed.totalByteFrames * 8L * 1_000_000L) / parsed.dsdSampleRateHz

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(parsed.channels)
                .setSampleRate(dec.outputSampleRateHz)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setAverageBitrate(dec.outputSampleRateHz * parsed.channels * 32)
                .setPeakBitrate(dec.outputSampleRateHz * parsed.channels * 32)
                .build()
        )
        extractorOutput?.endTracks()
        extractorOutput?.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val clamped = timeUs.coerceIn(0L, durationUs)
                val byteFramesPerSec = parsed.dsdSampleRateHz / 8L
                val rawFrame = ((clamped * byteFramesPerSec) / 1_000_000L).coerceIn(0L, parsed.totalByteFrames)
                val snappedFrame = if (parsed.kind == ContainerKind.DSF) {
                    (rawFrame / parsed.blockSizePerChannel) * parsed.blockSizePerChannel
                } else {
                    rawFrame
                }
                val pos = byteOffsetForFrame(parsed, snappedFrame)
                val pointUs = (snappedFrame * 8L * 1_000_000L) / parsed.dsdSampleRateHz
                return SeekMap.SeekPoints(SeekPoint(pointUs, pos))
            }
        })

        state = if (input.position == parsed.dataStart) STATE_READING else STATE_SEEK_TO_DATA
    }

    private fun parseDsfStream(input: ExtractorInput, first16: ByteArray): DsdStreamSpec {
        // Already read 16 bytes; read remaining 12 bytes of 28-byte DSD chunk.
        val restDsd = ByteArray(12)
        input.readFully(restDsd, 0, 12)
        val dsdChunkSize = AudioHeaderInspector.leU64(first16, 4).toInt().coerceAtLeast(28)
        if (dsdChunkSize > 28) {
            input.skipFully(dsdChunkSize - 28)
        }

        val fmtChunk = ByteArray(52)
        input.readFully(fmtChunk, 0, 52)
        if (String(fmtChunk, 0, 4, Charsets.US_ASCII) != "fmt ") {
            throw ParserException.createForMalformedContainer("Invalid DSF fmt chunk", null)
        }
        val fmtSize = AudioHeaderInspector.leU64(fmtChunk, 4).toInt().coerceAtLeast(52)
        if (fmtSize > 52) {
            input.skipFully(fmtSize - 52)
        }

        val formatId = AudioHeaderInspector.leU32(fmtChunk, 16).toInt()
        if (formatId != 0) {
            throw ParserException.createForUnsupportedContainerFeature("Unsupported DSF formatId: $formatId")
        }
        val channels = AudioHeaderInspector.leU32(fmtChunk, 24).toInt()
        if (channels !in 1..8) {
            throw ParserException.createForMalformedContainer("Unsupported DSF channels: $channels", null)
        }
        val dsdSampleRateHz = AudioHeaderInspector.leU32(fmtChunk, 28).toInt()
        if (dsdSampleRateHz < 1_000_000 || dsdSampleRateHz > 50_000_000) {
            throw ParserException.createForMalformedContainer("Invalid DSF sample rate: $dsdSampleRateHz", null)
        }
        val bitsPerSample = AudioHeaderInspector.leU32(fmtChunk, 32).toInt()
        val lsbFirst = (bitsPerSample == 1)
        val sampleCountPerCh = AudioHeaderInspector.leU64(fmtChunk, 36)
        val blockSizePerChannel = AudioHeaderInspector.leU32(fmtChunk, 44).toInt().coerceIn(512, 65536)

        val dataHead = ByteArray(12)
        input.readFully(dataHead, 0, 12)
        if (String(dataHead, 0, 4, Charsets.US_ASCII) != "data") {
            throw ParserException.createForMalformedContainer("Invalid DSF data chunk", null)
        }
        val dataChunkSize = AudioHeaderInspector.leU64(dataHead, 4)
        val rawAudioBytes = (dataChunkSize - 12L).coerceAtLeast(0L)
        val headerFrames = (sampleCountPerCh / 8L).coerceAtLeast(0L)
        val containerFrames = rawAudioBytes / channels
        val totalFrames = if (headerFrames > 0L) min(headerFrames, containerFrames) else containerFrames

        return DsdStreamSpec(
            kind = ContainerKind.DSF,
            channels = channels,
            dsdSampleRateHz = dsdSampleRateHz,
            lsbFirst = lsbFirst,
            blockSizePerChannel = blockSizePerChannel,
            dataStart = input.position,
            totalByteFrames = totalFrames,
            totalBlockBytes = rawAudioBytes,
        )
    }

    private fun parseDffStream(input: ExtractorInput): DsdStreamSpec {
        val chunkHead = ByteArray(12)
        var channels = 0
        var dsdSampleRateHz = 0
        var isUncompressed = true
        var dataStart = -1L
        var dataBytes = 0L

        while (input.readFully(chunkHead, 0, 12, true)) {
            val id = String(chunkHead, 0, 4, Charsets.US_ASCII)
            val size = AudioHeaderInspector.beU64(chunkHead, 4)
            if (size < 0L) throw ParserException.createForMalformedContainer("Invalid DFF chunk size", null)

            if (id == "PROP" && size >= 4L) {
                val propType = ByteArray(4)
                input.readFully(propType, 0, 4)
                if (String(propType, 0, 4, Charsets.US_ASCII) == "SND ") {
                    var leftInProp = size - 4L
                    val subHead = ByteArray(12)
                    while (leftInProp >= 12L) {
                        input.readFully(subHead, 0, 12)
                        val subId = String(subHead, 0, 4, Charsets.US_ASCII)
                        val subSize = AudioHeaderInspector.beU64(subHead, 4)
                        leftInProp -= 12L
                        when {
                            subId == "FS  " && subSize >= 4L -> {
                                val fs = ByteArray(4)
                                input.readFully(fs, 0, 4)
                                dsdSampleRateHz = AudioHeaderInspector.beU32(fs, 0).toInt()
                                if (subSize > 4L) skipFullyLong(input, subSize - 4L)
                            }
                            subId == "CHNL" && subSize >= 2L -> {
                                val ch = ByteArray(2)
                                input.readFully(ch, 0, 2)
                                channels = AudioHeaderInspector.beU16(ch, 0)
                                if (subSize > 2L) skipFullyLong(input, subSize - 2L)
                            }
                            subId == "CMPR" && subSize >= 4L -> {
                                val cmpr = ByteArray(4)
                                input.readFully(cmpr, 0, 4)
                                isUncompressed = String(cmpr, 0, 4, Charsets.US_ASCII) == "DSD "
                                if (subSize > 4L) skipFullyLong(input, subSize - 4L)
                            }
                            else -> skipFullyLong(input, subSize)
                        }
                        leftInProp -= subSize
                        if ((subSize and 1L) != 0L && leftInProp > 0L) {
                            input.skipFully(1)
                            leftInProp -= 1L
                        }
                    }
                    if (leftInProp > 0L) skipFullyLong(input, leftInProp)
                } else {
                    skipFullyLong(input, size - 4L)
                }
            } else if (id == "DSD ") {
                dataStart = input.position
                dataBytes = size
                break
            } else {
                skipFullyLong(input, size)
            }

            if ((size and 1L) != 0L) {
                input.skipFully(1)
            }
        }

        if (!isUncompressed) {
            throw ParserException.createForUnsupportedContainerFeature("Compressed DST DSDIFF is not supported")
        }
        if (channels !in 1..8 || dsdSampleRateHz < 1_000_000 || dataStart < 0L) {
            throw ParserException.createForMalformedContainer("Incomplete DSDIFF stream metadata", null)
        }

        return DsdStreamSpec(
            kind = ContainerKind.DFF,
            channels = channels,
            dsdSampleRateHz = dsdSampleRateHz,
            lsbFirst = false, // DSDIFF is MSB-first interleaved
            blockSizePerChannel = 1,
            dataStart = dataStart,
            totalByteFrames = dataBytes / channels,
            totalBlockBytes = dataBytes,
        )
    }

    private fun byteOffsetForFrame(s: DsdStreamSpec, byteFrame: Long): Long = when (s.kind) {
        ContainerKind.DSF -> {
            val blockIndex = byteFrame / s.blockSizePerChannel
            s.dataStart + blockIndex * s.channels * s.blockSizePerChannel
        }
        ContainerKind.DFF -> s.dataStart + byteFrame * s.channels
    }

    private fun skipFullyLong(input: ExtractorInput, length: Long) {
        var left = length
        while (left > 0L) {
            val step = min(left, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(step)
            left -= step.toLong()
        }
    }

    companion object {
        private const val STATE_HEADER = 0
        private const val STATE_SEEK_TO_DATA = 1
        private const val STATE_READING = 2
    }
}
