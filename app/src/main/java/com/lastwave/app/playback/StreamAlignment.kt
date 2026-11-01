package com.lastwave.app.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Captures decoded PCM from a silent probe player as an 8 kHz mono signal,
 * binned by presentation time. Two probes started at the same position on
 * fresh players share a timestamp base, so their signals can be compared
 * directly to find how far apart two masters of one song are.
 */
internal class SwapPcmProbe(private val capacityBins: Int = SwapAlignment.CAPTURE_BINS) {
    private val lock = Any()
    private val sums = FloatArray(capacityBins)
    private val counts = IntArray(capacityBins)
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rateHz = 0
    private var firstPtsUs = Long.MIN_VALUE
    private var lastPtsUs = Long.MIN_VALUE
    private var filled = 0

    @Volatile var unsupported = false
        private set

    val isComplete: Boolean get() = synchronized(lock) { filled >= capacityBins }

    fun configure(format: Format) = synchronized(lock) {
        encoding = format.pcmEncoding
        channels = format.channelCount
        rateHz = format.sampleRate
        unsupported = rateHz <= 0 || channels <= 0 || encoding !in SUPPORTED_ENCODINGS
    }

    fun accept(buffer: ByteBuffer, ptsUs: Long) = synchronized(lock) {
        // The sink re-offers a buffer it could not fully consume.
        if (unsupported || rateHz <= 0 || ptsUs <= lastPtsUs || filled >= capacityBins) return@synchronized
        lastPtsUs = ptsUs
        if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = ptsUs
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytes = bytesPerSample(encoding)
        val frames = view.remaining() / (bytes * channels)
        val startBin = (ptsUs - firstPtsUs) * SwapAlignment.BIN_RATE_HZ / 1_000_000L
        for (frame in 0 until frames) {
            var mono = 0f
            for (ch in 0 until channels) mono += readSample(view, encoding)
            val bin = startBin + frame.toLong() * SwapAlignment.BIN_RATE_HZ / rateHz
            if (bin >= capacityBins) continue
            val index = bin.toInt()
            sums[index] += mono / channels
            counts[index]++
            if (index + 1 > filled) filled = index + 1
        }
    }

    fun snapshot(): SwapCapture? = synchronized(lock) {
        if (firstPtsUs == Long.MIN_VALUE || filled == 0) return@synchronized null
        val signal = FloatArray(filled) { i -> if (counts[i] > 0) sums[i] / counts[i] else 0f }
        SwapCapture(signal, firstPtsUs)
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        else -> 4
    }

    private fun readSample(view: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> view.short / 32768f
        C.ENCODING_PCM_24BIT -> {
            val low = view.get().toInt() and 0xFF
            val mid = view.get().toInt() and 0xFF
            val high = view.get().toInt()
            ((high shl 16) or (mid shl 8) or low) / 8388608f
        }
        C.ENCODING_PCM_32BIT -> view.int / 2147483648f
        else -> view.float
    }

    private companion object {
        val SUPPORTED_ENCODINGS = setOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
    }
}

/** Observes decoded PCM on its way to the real sink without changing it. */
@UnstableApi
internal class TappingAudioSink(
    sink: AudioSink,
    private val probe: SwapPcmProbe,
) : ForwardingAudioSink(sink) {
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        probe.configure(inputFormat)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        probe.accept(buffer, presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}

internal class SwapCapture(val signal: FloatArray, val firstPtsUs: Long)

internal object SwapAlignment {
    const val BIN_RATE_HZ = 8_000
    const val CAPTURE_BINS = BIN_RATE_HZ * 6

    private const val COARSE_FACTOR = 8
    private const val MAX_LAG_MS = 2_000
    private const val TEMPLATE_START_MS = 2_000
    private const val TEMPLATE_MS = 2_000
    private const val REFINE_BINS = 16
    private const val MIN_CONFIDENCE = 0.45
    private const val MIN_RMS = 0.002
    private const val MAX_START_SKEW_US = 1_000_000L

    /**
     * How far [candidate]'s timeline runs ahead of [reference]'s, in ms: the
     * audio at reference position P sits at candidate position P + result.
     * Null when the match is too weak to trust (silence, a different master).
     */
    fun measureLagMs(reference: SwapCapture, candidate: SwapCapture): Long? {
        val startSkewUs = candidate.firstPtsUs - reference.firstPtsUs
        if (abs(startSkewUs) > MAX_START_SKEW_US) return null

        val coarseRef = downsample(reference.signal, COARSE_FACTOR)
        val coarseCand = downsample(candidate.signal, COARSE_FACTOR)
        val coarseRate = BIN_RATE_HZ / COARSE_FACTOR
        val templateStart = TEMPLATE_START_MS * coarseRate / 1_000
        val templateSize = TEMPLATE_MS * coarseRate / 1_000
        val maxLag = MAX_LAG_MS * coarseRate / 1_000
        if (coarseRef.size < templateStart + templateSize) return null
        if (rms(coarseRef, templateStart, templateSize) < MIN_RMS) return null

        val coarse = bestLag(coarseRef, coarseCand, templateStart, templateSize, -maxLag, maxLag) ?: return null
        val fineStart = templateStart * COARSE_FACTOR
        val fineSize = templateSize * COARSE_FACTOR
        val center = coarse.lag * COARSE_FACTOR
        val fine = bestLag(
            reference.signal,
            candidate.signal,
            fineStart,
            fineSize,
            center - REFINE_BINS,
            center + REFINE_BINS,
        ) ?: return null
        if (fine.score < MIN_CONFIDENCE) return null
        return startSkewUs / 1_000L + fine.lag * 1_000L / BIN_RATE_HZ
    }

    private class Match(val lag: Int, val score: Double)

    private fun bestLag(
        reference: FloatArray,
        candidate: FloatArray,
        start: Int,
        size: Int,
        minLag: Int,
        maxLag: Int,
    ): Match? {
        var best: Match? = null
        var refEnergy = 0.0
        for (i in 0 until size) refEnergy += reference[start + i].toDouble() * reference[start + i]
        if (refEnergy <= 0.0) return null
        for (lag in minLag..maxLag) {
            val offset = start + lag
            if (offset < 0 || offset + size > candidate.size) continue
            var dot = 0.0
            var energy = 0.0
            for (i in 0 until size) {
                val c = candidate[offset + i].toDouble()
                dot += reference[start + i] * c
                energy += c * c
            }
            if (energy <= 0.0) continue
            // Absolute value: a master with inverted polarity is still the same audio.
            val score = abs(dot) / sqrt(refEnergy * energy)
            if (best == null || score > best.score) best = Match(lag, score)
        }
        return best
    }

    private fun downsample(signal: FloatArray, factor: Int): FloatArray =
        FloatArray(signal.size / factor) { block ->
            var sum = 0f
            for (i in 0 until factor) sum += signal[block * factor + i]
            sum / factor
        }

    private fun rms(signal: FloatArray, start: Int, size: Int): Double {
        var energy = 0.0
        for (i in start until start + size) energy += signal[i].toDouble() * signal[i]
        return sqrt(energy / size)
    }
}
