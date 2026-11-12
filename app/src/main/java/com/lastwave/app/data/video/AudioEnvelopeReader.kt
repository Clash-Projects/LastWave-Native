package com.lastwave.app.data.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val DOWNLOAD_CHUNK_BYTES = 1024L * 1024L
private const val CODEC_TIMEOUT_US = 10_000L

/** Downloads the start of an audio stream and reduces it to a loudness envelope. */
internal class AudioEnvelopeReader(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {
    suspend fun read(
        url: String,
        headers: Map<String, String>,
        bitrate: Int,
        seconds: Int,
    ): FloatArray {
        val file = File.createTempFile("sync_align", ".audio", cacheDir)
        try {
            download(url, headers, maxBytes = bitrate.toLong() * seconds / 8, file)
            return decodeRms(file, seconds)
        } finally {
            file.delete()
        }
    }

    /** Fetches bounded ranges, as the player does, because YouTube throttles open-ended ones. */
    private suspend fun download(url: String, headers: Map<String, String>, maxBytes: Long, file: File) {
        file.outputStream().use { output ->
            var position = 0L
            while (position < maxBytes) {
                currentCoroutineContext().ensureActive()
                val end = minOf(position + DOWNLOAD_CHUNK_BYTES, maxBytes) - 1
                val request = Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=$position-$end")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
                val received = http.newCall(request).execute().use { response ->
                    if (response.code == 416) return
                    // Anything but a partial response could be the whole file, which would be far too large.
                    if (response.code != 206) throw IOException("Audio download failed with HTTP ${response.code}")
                    response.body?.byteStream()?.copyTo(output) ?: 0L
                }
                if (received <= 0L) return
                position += received
            }
        }
    }

    private suspend fun decodeRms(file: File, seconds: Int): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track to align")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            return drain(extractor, decoder, format, seconds)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private suspend fun drain(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        inputFormat: MediaFormat,
        seconds: Int,
    ): FloatArray {
        val hops = ArrayList<Float>(seconds * 1_000 / ALIGNMENT_HOP_MS)
        val maxHops = seconds * 1_000 / ALIGNMENT_HOP_MS
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var energy = 0.0
        var samplesInHop = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false

        while (hops.size < maxHops) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    // A cut-off download can fail the last read; treat that as the end of the audio.
                    val size = runCatching { extractor.readSampleData(decoder.getInputBuffer(inputIndex)!!, 0) }
                        .getOrDefault(-1)
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outputIndex >= 0) {
                val samplesPerHop = sampleRate * channels * ALIGNMENT_HOP_MS / 1_000
                val pcm = decoder.getOutputBuffer(outputIndex)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (pcm.hasRemaining()) {
                    val sample = pcm.get() / 32_768.0
                    energy += sample * sample
                    if (++samplesInHop >= samplesPerHop) {
                        hops += sqrt(energy / samplesInHop).toFloat()
                        energy = 0.0
                        samplesInHop = 0
                    }
                }
                decoder.releaseOutputBuffer(outputIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return hops.toFloatArray()
    }
}
