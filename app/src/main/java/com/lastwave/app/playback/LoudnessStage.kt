package com.lastwave.app.playback

import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan
import kotlinx.coroutines.flow.StateFlow

/**
 * Loudness normalization on the native Float32 output, i.e. ordinary playback.
 * [AudioEffectsEngine] only runs when native DSP is unavailable, so without
 * this stage the setting never reached normal playback at all.
 *
 * Tagged files use their ReplayGain values. Streams carry no tags, so their
 * integrated loudness is measured live (BS.1770 K-weighting, EBU R128 gating)
 * and the gain glides toward [LOUDNESS_TARGET_LUFS]. In ALBUM mode the
 * measurement keeps running across consecutive tracks of one album, so a quiet
 * interlude stays quieter than the album's loud tracks.
 *
 * Renderer thread only; the two StateFlows are only read.
 */
class LoudnessStage(
    private val settings: StateFlow<LoudnessSettings>,
    private val albumKey: StateFlow<String?>,
) {
    private val meter = LoudnessMeter()
    private var tags = ReplayGainTags()
    private var meterAlbumKey: String? = null
    private var gainDb = 0f

    /** Called from AudioSink.configure: Media3 passes the container metadata there. */
    fun beginTrack(format: Format) {
        tags = LoudnessNormalizer.parseFromFormat(format)
        val key = albumKey.value
        val sameAlbum = settings.value.mode == LoudnessMode.ALBUM && key != null && key == meterAlbumKey
        if (!sameAlbum) {
            meter.reset()
            meterAlbumKey = key
            // Start the unmeasured track no louder than unity: a brief dip
            // while the meter settles is far less jarring than a blast.
            gainDb = min(gainDb, 0f)
        }
    }

    /** Scales [output] (interleaved native-order Float32) in place. */
    fun process(output: ByteBuffer, channelCount: Int, sampleRate: Int) {
        if (channelCount !in 1..2 || sampleRate <= 0) return
        val current = settings.value
        if (current.mode == LoudnessMode.OFF) {
            if (gainDb == 0f) return
        } else if (current.mode == LoudnessMode.ALBUM) {
            val key = albumKey.value
            if (key != meterAlbumKey) {
                meter.reset()
                meterAlbumKey = key
            }
        }

        val samples = output.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = samples.remaining() / channelCount
        if (frames == 0) return

        val targetDb: Float
        val timeConstantSec: Float
        if (current.mode == LoudnessMode.OFF) {
            targetDb = 0f
            timeConstantSec = TAG_TIME_CONSTANT_SEC
        } else if (tags.hasGain) {
            meter.trackPeak(samples, channelCount)
            targetDb = LoudnessNormalizer.gainForTags(tags, current.mode, current.preampDb)
            timeConstantSec = TAG_TIME_CONSTANT_SEC
        } else {
            meter.measure(samples, channelCount, sampleRate)
            val measured = meter.integratedLufs
            targetDb = if (measured == null) {
                gainDb
            } else {
                LoudnessNormalizer.gainForMeasuredLufs(
                    measuredLufs = measured,
                    preampDb = current.preampDb,
                    peakLinear = meter.peak,
                ).coerceAtMost(MAX_MEASURED_BOOST_DB)
            }
            timeConstantSec = if (targetDb < gainDb) ATTACK_TIME_CONSTANT_SEC else RELEASE_TIME_CONSTANT_SEC
        }

        // A new peak that the current boost would clip is cut at once; the
        // smoothed glide would let the first loud transient distort.
        val startDb = if (gainDb > 0f && meter.peak > 0f) {
            min(gainDb, max(0f, -20f * log10(meter.peak)))
        } else {
            gainDb
        }
        val blend = 1f - exp(-(frames.toFloat() / sampleRate) / timeConstantSec)
        val endDb = startDb + (targetDb - startDb) * blend
        gainDb = endDb
        applyRamp(samples, channelCount, frames, dbToLinear(startDb), dbToLinear(endDb))
    }

    private fun applyRamp(samples: FloatBuffer, channelCount: Int, frames: Int, from: Float, to: Float) {
        if (from == 1f && to == 1f) return
        val base = samples.position()
        val step = (to - from) / frames
        for (frame in 0 until frames) {
            val gain = from + step * frame
            val index = base + frame * channelCount
            for (channel in 0 until channelCount) {
                val scaled = samples.get(index + channel) * gain
                samples.put(index + channel, if (gain > 1f) scaled.coerceIn(-1f, 1f) else scaled)
            }
        }
    }

    private fun dbToLinear(db: Float): Float = if (db == 0f) 1f else 10f.pow(db / 20f)

    private companion object {
        /** Tags are known up front, so only a click-free step is needed. */
        const val TAG_TIME_CONSTANT_SEC = 0.03f
        const val ATTACK_TIME_CONSTANT_SEC = 0.3f
        const val RELEASE_TIME_CONSTANT_SEC = 1.5f
        /** A quiet intro reads low; a large boost would then hit the drop hard. */
        const val MAX_MEASURED_BOOST_DB = 6f
    }
}

/** BS.1770-4 integrated loudness with EBU R128 absolute and relative gates. */
private class LoudnessMeter {
    private val filters = arrayOf(KWeightingFilter(), KWeightingFilter())
    private var sampleRate = 0
    private var subBlockFrames = 0
    private var subBlockFill = 0
    private var subBlockEnergy = 0.0
    private val recentSubBlocks = DoubleArray(SUB_BLOCKS_PER_BLOCK)
    private var recentCount = 0
    private var recentIndex = 0
    private val histogramCount = IntArray(HISTOGRAM_BINS)
    private val histogramEnergy = DoubleArray(HISTOGRAM_BINS)
    private var gatedBlocks = 0

    var peak = 0f
        private set
    var integratedLufs: Float? = null
        private set

    fun reset() {
        filters.forEach(KWeightingFilter::reset)
        subBlockFill = 0
        subBlockEnergy = 0.0
        recentCount = 0
        recentIndex = 0
        histogramCount.fill(0)
        histogramEnergy.fill(0.0)
        gatedBlocks = 0
        peak = 0f
        integratedLufs = null
    }

    fun trackPeak(samples: FloatBuffer, channelCount: Int) {
        val base = samples.position()
        val count = (samples.remaining() / channelCount) * channelCount
        for (i in 0 until count) peak = max(peak, abs(samples.get(base + i)))
    }

    fun measure(samples: FloatBuffer, channelCount: Int, rate: Int) {
        if (rate != sampleRate) {
            sampleRate = rate
            filters.forEach { it.configure(rate) }
            subBlockFrames = rate / 10
            subBlockFill = 0
            subBlockEnergy = 0.0
        }
        // A mono stream plays on both speakers: BS.1770 dual-mono weighting.
        val channelWeight = if (channelCount == 1) 2.0 else 1.0
        val base = samples.position()
        val frames = samples.remaining() / channelCount
        for (frame in 0 until frames) {
            var energy = 0.0
            for (channel in 0 until channelCount) {
                val sample = samples.get(base + frame * channelCount + channel)
                peak = max(peak, abs(sample))
                val weighted = filters[channel].process(sample.toDouble())
                energy += weighted * weighted
            }
            subBlockEnergy += energy * channelWeight
            if (++subBlockFill == subBlockFrames) closeSubBlock()
        }
    }

    /** 400 ms blocks with 75 % overlap, assembled from 100 ms sub-blocks. */
    private fun closeSubBlock() {
        recentSubBlocks[recentIndex] = subBlockEnergy / subBlockFrames
        recentIndex = (recentIndex + 1) % SUB_BLOCKS_PER_BLOCK
        if (recentCount < SUB_BLOCKS_PER_BLOCK) recentCount++
        subBlockFill = 0
        subBlockEnergy = 0.0
        if (recentCount < SUB_BLOCKS_PER_BLOCK) return

        val blockEnergy = recentSubBlocks.average()
        val blockLufs = energyToLufs(blockEnergy)
        if (!blockLufs.isFinite() || blockLufs <= ABSOLUTE_GATE_LUFS) return
        val bin = ((blockLufs - ABSOLUTE_GATE_LUFS) * BINS_PER_LU).toInt().coerceIn(0, HISTOGRAM_BINS - 1)
        histogramCount[bin]++
        histogramEnergy[bin] += blockEnergy
        if (++gatedBlocks >= MIN_BLOCKS_FOR_ESTIMATE) integratedLufs = computeIntegrated()
    }

    private fun computeIntegrated(): Float? {
        val relativeGate = energyToLufs(sumEnergy(0) / gatedBlocks) + RELATIVE_GATE_LU
        val firstBin = ceil((relativeGate - ABSOLUTE_GATE_LUFS) * BINS_PER_LU).toInt().coerceIn(0, HISTOGRAM_BINS - 1)
        var count = 0
        for (bin in firstBin until HISTOGRAM_BINS) count += histogramCount[bin]
        if (count == 0) return null
        return energyToLufs(sumEnergy(firstBin) / count).toFloat().takeIf { it.isFinite() }
    }

    private fun sumEnergy(fromBin: Int): Double {
        var sum = 0.0
        for (bin in fromBin until HISTOGRAM_BINS) sum += histogramEnergy[bin]
        return sum
    }

    private fun energyToLufs(energy: Double): Double = -0.691 + 10.0 * log10(energy)

    private companion object {
        const val SUB_BLOCKS_PER_BLOCK = 4
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = -10.0
        const val BINS_PER_LU = 10.0
        /** 0.1 LU bins spanning -70 .. +30 LUFS. */
        const val HISTOGRAM_BINS = 1_000
        /** ~0.8 s of audio before the first estimate drives the gain. */
        const val MIN_BLOCKS_FOR_ESTIMATE = 5
    }
}

/** BS.1770 K-weighting: high-shelf pre-filter then RLB high-pass, any sample rate. */
private class KWeightingFilter {
    private val shelf = DoubleArray(5)
    private val highPass = DoubleArray(5)
    private var shelfZ1 = 0.0
    private var shelfZ2 = 0.0
    private var highPassZ1 = 0.0
    private var highPassZ2 = 0.0

    fun configure(rate: Int) {
        // Coefficient derivation from libebur128, exact at 48 kHz and valid at any rate.
        var k = tan(PI * 1681.974450955533 / rate)
        val vh = 10.0.pow(3.999843853973347 / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var q = 0.7071752369554196
        var a0 = 1.0 + k / q + k * k
        shelf[0] = (vh + vb * k / q + k * k) / a0
        shelf[1] = 2.0 * (k * k - vh) / a0
        shelf[2] = (vh - vb * k / q + k * k) / a0
        shelf[3] = 2.0 * (k * k - 1.0) / a0
        shelf[4] = (1.0 - k / q + k * k) / a0

        k = tan(PI * 38.13547087602444 / rate)
        q = 0.5003270373238773
        a0 = 1.0 + k / q + k * k
        highPass[0] = 1.0
        highPass[1] = -2.0
        highPass[2] = 1.0
        highPass[3] = 2.0 * (k * k - 1.0) / a0
        highPass[4] = (1.0 - k / q + k * k) / a0
        reset()
    }

    fun reset() {
        shelfZ1 = 0.0
        shelfZ2 = 0.0
        highPassZ1 = 0.0
        highPassZ2 = 0.0
    }

    fun process(x: Double): Double {
        val s = shelf[0] * x + shelfZ1
        shelfZ1 = shelf[1] * x - shelf[3] * s + shelfZ2
        shelfZ2 = shelf[2] * x - shelf[4] * s
        val y = highPass[0] * s + highPassZ1
        highPassZ1 = highPass[1] * s - highPass[3] * y + highPassZ2
        highPassZ2 = highPass[2] * s - highPass[4] * y
        return y
    }
}
