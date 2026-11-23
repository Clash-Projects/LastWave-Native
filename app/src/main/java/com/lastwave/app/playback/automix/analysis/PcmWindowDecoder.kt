package com.lastwave.app.playback.automix.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Where to read a stream for analysis. The player supplies its own cached,
 * range-chunked data source, so analysis obeys the same googlevideo rules
 * (bounded ranges, minting client's User-Agent) and shares cached bytes.
 */
class AnalysisInput(val factory: DataSource.Factory, val dataSpec: DataSpec)

/** Decodes stretches of a stream to mono float PCM at ~11 kHz with exact media timestamps. */
internal class PcmWindowDecoder(input: AnalysisInput) : Closeable {
    class Window(val pcm: FloatArray, val sampleRate: Int, val startMs: Double)

    private val source = DataSourceMedia(input)
    private val extractor = MediaExtractor()
    private val format: MediaFormat
    val durationMs: Long

    init {
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            format = extractor.getTrackFormat(track)
            durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun decode(fromMs: Long, lengthMs: Long): Window? {
        val fromUs = fromMs.coerceAtLeast(0L) * 1000
        val untilUs = fromUs + lengthMs * 1000
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            return drain(codec, fromUs, untilUs)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private suspend fun drain(codec: MediaCodec, fromUs: Long, untilUs: Long): Window? {
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var decimation = decimationFor(rate)
        var output = FloatArray(((untilUs - fromUs) * rate / 1_000_000 / decimation + 1024).toInt())
        var written = 0
        var startUs = -1L
        var accumulator = 0.0
        var accumulated = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleAfterInput = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0 || extractor.sampleTime > untilUs) {
                        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val changed = codec.outputFormat
                rate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                encoding = if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    changed.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                if (written == 0) decimation = decimationFor(rate)
                continue
            }
            if (index < 0) {
                // Some decoders never flag end-of-stream after the last input.
                if (inputDone && ++idleAfterInput > MAX_IDLE_POLLS) break
                continue
            }
            idleAfterInput = 0
            val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
            val frameBytes = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frames = info.size / frameBytes
            var frame = 0
            // Samples decoded from the preceding sync point are dropped to the exact start.
            if (info.presentationTimeUs < fromUs) {
                frame = ((fromUs - info.presentationTimeUs) * rate / 1_000_000).toInt().coerceAtMost(frames)
            }
            if (startUs < 0 && frame < frames) startUs = info.presentationTimeUs + frame * 1_000_000L / rate
            val base = info.offset
            while (frame < frames) {
                var mono = 0.0
                for (channel in 0 until channels) {
                    val position = base + (frame * channels + channel) * (frameBytes / channels)
                    mono += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        buffer.getFloat(position).toDouble()
                    } else {
                        buffer.getShort(position) / 32_768.0
                    }
                }
                accumulator += mono / channels
                if (++accumulated == decimation) {
                    if (written == output.size) output = output.copyOf(output.size * 2)
                    output[written++] = (accumulator / decimation).toFloat()
                    accumulator = 0.0
                    accumulated = 0
                }
                frame++
            }
            val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val bufferEndUs = info.presentationTimeUs + frames * 1_000_000L / rate
            codec.releaseOutputBuffer(index, false)
            if (endOfStream || bufferEndUs >= untilUs) break
        }
        if (startUs < 0 || written == 0) return null
        return Window(output.copyOf(written), rate / decimation, startUs / 1000.0)
    }

    override fun close() {
        runCatching { extractor.release() }
        runCatching { source.close() }
    }

    private fun decimationFor(rate: Int) = (rate / TARGET_RATE_HZ).roundToInt().coerceAtLeast(1)

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS = 200
        const val TARGET_RATE_HZ = 11_025.0
    }
}

/**
 * MediaExtractor over a Media3 DataSource. Short forward gaps are read
 * through instead of reopening, so header and index reads do not each cost a
 * new HTTP range request.
 */
private class DataSourceMedia(private val input: AnalysisInput) : MediaDataSource() {
    private var source: DataSource? = null
    private var position = 0L
    private var length = C.LENGTH_UNSET.toLong()
    private val skipBuffer = ByteArray(16 * 1024)

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, size: Int): Int {
        if (size == 0) return 0
        if (length != C.LENGTH_UNSET.toLong() && offset >= length) return -1
        val open = source
        if (open == null || offset < position || offset - position > MAX_SKIP_BYTES) {
            reopen(offset)
        } else {
            while (position < offset) {
                val read = open.read(skipBuffer, 0, minOf(skipBuffer.size.toLong(), offset - position).toInt())
                if (read == C.RESULT_END_OF_INPUT) return -1
                position += read
            }
        }
        val read = source!!.read(buffer, bufferOffset, size)
        if (read == C.RESULT_END_OF_INPUT) return -1
        position += read
        return read
    }

    override fun getSize(): Long {
        if (length == C.LENGTH_UNSET.toLong() && source == null) reopen(0)
        return if (length == C.LENGTH_UNSET.toLong()) -1 else length
    }

    private fun reopen(offset: Long) {
        closeSource()
        val created = input.factory.createDataSource()
        source = created
        val opened = created.open(input.dataSpec.subrange(offset))
        if (opened != C.LENGTH_UNSET.toLong()) length = offset + opened
        position = offset
    }

    private fun closeSource() {
        runCatching { source?.close() }
        source = null
    }

    override fun close() = closeSource()

    private companion object {
        const val MAX_SKIP_BYTES = 256 * 1024L
    }
}
