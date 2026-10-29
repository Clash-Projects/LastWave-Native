package com.lastwave.app.playback.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM-testable DSP and meter math for the playback analysis package.
 *
 * The production DSP runs in C++ over JNI and cannot execute in local unit
 * tests, so this file provides small Kotlin equivalents built from standard
 * textbook formulas (RBJ biquad cookbook, 20*log10 level conversion,
 * hard-ceiling limiting). They exist so [DspResponseTest] can verify
 * convergence, bypass exactness, limiter ceilings and meter arithmetic on
 * the JVM with no NDK or device. Nothing here is wired into the audible
 * path; it performs no audio I/O.
 */
object DspMath {

    const val CLIP_THRESHOLD_LINEAR = 1.0f
    const val MIN_DBFS = -120.0f

    /** Q used by the 15-band equalizer stages. */
    const val EQ_Q = 1.4142135623730951

    /** Center frequencies of the 15 equalizer bands in Hz. */
    val EQ_FREQUENCIES_HZ: List<Double> = listOf(
        25.0, 40.0, 63.0, 100.0, 160.0,
        250.0, 400.0, 630.0, 1000.0, 1600.0,
        2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
    )

    /** Converts a linear amplitude to dBFS, floored at [MIN_DBFS]. */
    fun amplitudeToDbfs(linear: Float): Float {
        if (!linear.isFinite() || linear <= 0f) return MIN_DBFS
        return (20.0 * log10(linear.toDouble())).toFloat().coerceAtLeast(MIN_DBFS)
    }

    /** Summary of one measurement window over interleaved Float32 samples. */
    data class WindowStats(
        val sampleCount: Long,
        val peakLinear: Float,
        val rmsLinear: Float,
        val dcOffset: Float,
        val clippedSamples: Long,
    )

    /**
     * Batch analysis over [samples]. Read-only: the input array is never
     * written to. Non-finite samples are counted as clipped and excluded
     * from the sums so one bad value cannot poison the window.
     */
    fun analyzeWindow(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): WindowStats {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "Window [$offset, ${offset + length}) is outside ${samples.size} samples"
        }
        if (length == 0) return WindowStats(0L, 0f, 0f, 0f, 0L)
        var peak = 0f
        var sum = 0.0
        var sumSquares = 0.0
        var finiteCount = 0L
        var clips = 0L
        for (index in offset until offset + length) {
            val value = samples[index]
            if (!value.isFinite()) {
                clips++
                continue
            }
            val magnitude = abs(value)
            if (magnitude > peak) peak = magnitude
            sum += value
            sumSquares += value.toDouble() * value.toDouble()
            finiteCount++
            if (magnitude >= CLIP_THRESHOLD_LINEAR) clips++
        }
        if (finiteCount == 0L) return WindowStats(length.toLong(), 0f, 0f, 0f, clips)
        val rms = sqrt(sumSquares / finiteCount.toDouble()).toFloat()
        val dc = (sum / finiteCount.toDouble()).toFloat()
        return WindowStats(
            sampleCount = length.toLong(),
            peakLinear = peak,
            rmsLinear = rms,
            dcOffset = dc,
            clippedSamples = clips,
        )
    }

    /** Decibel gain to linear multiplier. Non-finite input maps to unity. */
    fun dbToGain(gainDb: Double): Double {
        if (!gainDb.isFinite()) return 1.0
        return 10.0.pow(gainDb / 20.0)
    }

    /** Linear multiplier to decibels. Non-positive input maps to -infinity. */
    fun gainToDb(gain: Double): Double {
        if (!gain.isFinite() || gain <= 0.0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(gain)
    }

    /**
     * Applies a ReplayGain-style adjustment and returns a new array. The
     * input is never mutated. Non-finite input samples become silence so
     * they cannot propagate downstream.
     */
    fun applyGainDb(samples: FloatArray, gainDb: Double): FloatArray {
        val gain = dbToGain(gainDb).toFloat()
        return FloatArray(samples.size) { index ->
            val value = samples[index]
            if (!value.isFinite()) 0f else value * gain
        }
    }

    /** Second-order IIR section with per-channel state for mono/stereo use. */
    class JvmBiquad private constructor(
        private var b0: Double,
        private var b1: Double,
        private var b2: Double,
        private var a1: Double,
        private var a2: Double,
    ) {
        private val z1 = DoubleArray(2)
        private val z2 = DoubleArray(2)

        fun reset() {
            z1.fill(0.0)
            z2.fill(0.0)
        }

        /**
         * Filters one sample on [channel] (0 or 1). Guards against
         * denormals and non-finite state so a hostile input cannot wedge
         * the section; on non-finite output the state resets and 0 is
         * returned.
         */
        fun tick(input: Float, channel: Int = 0): Float {
            require(channel == 0 || channel == 1) { "channel must be 0 or 1" }
            val output = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * output + z2[channel]
            z2[channel] = b2 * input - a2 * output
            if (abs(z1[channel]) < 1.0e-20) z1[channel] = 0.0
            if (abs(z2[channel]) < 1.0e-20) z2[channel] = 0.0
            if (!output.isFinite() || !z1[channel].isFinite() || !z2[channel].isFinite()) {
                z1[channel] = 0.0
                z2[channel] = 0.0
                return 0f
            }
            return output.toFloat()
        }

        

        /** Steady-state magnitude response at [frequencyHz]. */
        fun magnitude(sampleRateHz: Double, frequencyHz: Double): Double {
            val safeRate = sampleRateHz.coerceAtLeast(1.0)
            val clamped = frequencyHz.coerceIn(1.0, safeRate * 0.45)
            val omega = 2.0 * PI * clamped / safeRate
            val cos1 = cos(omega)
            val sin1 = sin(omega)
            val cos2 = cos(2.0 * omega)
            val sin2 = sin(2.0 * omega)
            val numReal = b0 + b1 * cos1 + b2 * cos2
            val numImag = -b1 * sin1 - b2 * sin2
            val denReal = 1.0 + a1 * cos1 + a2 * cos2
            val denImag = -a1 * sin1 - a2 * sin2
            val numPower = numReal * numReal + numImag * numImag
            val denPower = denReal * denReal + denImag * denImag
            return sqrt(numPower / denPower.coerceAtLeast(1.0e-24))
        }

        companion object {
            private fun clampRate(sampleRateHz: Double): Double =
                sampleRateHz.coerceAtLeast(8000.0)

            private fun clampFrequency(sampleRateHz: Double, frequencyHz: Double): Double =
                frequencyHz.coerceIn(1.0, sampleRateHz * 0.45)

            fun highPass(sampleRateHz: Double, frequencyHz: Double, q: Double): JvmBiquad {
                val rate = clampRate(sampleRateHz)
                val frequency = clampFrequency(rate, frequencyHz)
                val safeQ = q.coerceAtLeast(0.01)
                val omega = 2.0 * PI * frequency / rate
                val cosine = cos(omega)
                val alpha = sin(omega) / (2.0 * safeQ)
                val a0 = 1.0 + alpha
                return JvmBiquad(
                    b0 = ((1.0 + cosine) * 0.5) / a0,
                    b1 = -(1.0 + cosine) / a0,
                    b2 = ((1.0 + cosine) * 0.5) / a0,
                    a1 = (-2.0 * cosine) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }

            fun peaking(
                sampleRateHz: Double,
                frequencyHz: Double,
                q: Double,
                gainDb: Double,
            ): JvmBiquad {
                val rate = clampRate(sampleRateHz)
                val frequency = clampFrequency(rate, frequencyHz)
                val safeQ = q.coerceAtLeast(0.01)
                val safeGain = if (gainDb.isFinite()) gainDb else 0.0
                val omega = 2.0 * PI * frequency / rate
                val cosine = cos(omega)
                val alpha = sin(omega) / (2.0 * safeQ)
                val amplitude = 10.0.pow(safeGain / 40.0)
                val a0 = 1.0 + alpha / amplitude
                val b0 = (1.0 + alpha * amplitude) / a0
                val b1 = (-2.0 * cosine) / a0
                val b2 = (1.0 - alpha * amplitude) / a0
                return JvmBiquad(
                    b0 = b0,
                    b1 = b1,
                    b2 = b2,
                    a1 = b1,
                    a2 = (1.0 - alpha / amplitude) / a0,
                )
            }

            fun highShelf(
                sampleRateHz: Double,
                frequencyHz: Double,
                slope: Double,
                gainDb: Double,
            ): JvmBiquad {
                val rate = clampRate(sampleRateHz)
                val frequency = clampFrequency(rate, frequencyHz)
                val safeGain = if (gainDb.isFinite()) gainDb else 0.0
                val safeSlope = slope.coerceIn(0.1, 1.0)
                val omega = 2.0 * PI * frequency / rate
                val cosine = cos(omega)
                val sine = sin(omega)
                val amplitude = 10.0.pow(safeGain / 40.0)
                val alpha = (sine * 0.5) * sqrt(
                    (amplitude + 1.0 / amplitude) * (1.0 / safeSlope - 1.0) + 2.0,
                )
                val beta = 2.0 * sqrt(amplitude) * alpha
                val a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta
                return JvmBiquad(
                    b0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta) / a0,
                    b1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine) / a0,
                    b2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta) / a0,
                    a1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine) / a0,
                    a2 = ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta) / a0,
                )
            }

            /** Unity section used to model a bypassed stage. */
            fun passthrough(): JvmBiquad = JvmBiquad(1.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** One named filter stage in the test chain. */
    data class NamedStage(val name: String, val filter: JvmBiquad)

    /**
     * The fixed clarity-chain stage list evaluated at [sampleRateHz].
     * Parameters match the production chain layout so the JVM test covers
     * the same stage shapes (two high-pass, five peaking/shelf stages plus
     * the mono-bass and exciter high-pass pair).
     */
    fun clarityChain(sampleRateHz: Double): List<NamedStage> = listOf(
        NamedStage("subBassHighPass", JvmBiquad.highPass(sampleRateHz, 24.0, 0.7071067811865476)),
        NamedStage("bassFoundation", JvmBiquad.peaking(sampleRateHz, 72.0, 0.80, 3.2)),
        NamedStage("lowMidSeparation", JvmBiquad.peaking(sampleRateHz, 280.0, 0.90, -3.0)),
        NamedStage("boxinessControl", JvmBiquad.peaking(sampleRateHz, 750.0, 0.85, -1.4)),
        NamedStage("presenceDetail", JvmBiquad.peaking(sampleRateHz, 3400.0, 0.85, 3.8)),
        NamedStage("airDetail", JvmBiquad.highShelf(sampleRateHz, 10500.0, 0.85, 4.8)),
        NamedStage("monoBassFilter", JvmBiquad.highPass(sampleRateHz, 130.0, 0.7071067811865476)),
        NamedStage("airExciterFilter", JvmBiquad.highPass(sampleRateHz, 6000.0, 0.7071067811865476)),
    )

    /** One equalizer band stage for the convergence test. */
    fun eqBand(sampleRateHz: Double, frequencyHz: Double, gainDb: Double): JvmBiquad =
        JvmBiquad.peaking(sampleRateHz, frequencyHz, EQ_Q, gainDb)

    /** Sample-exact bypass: copies input to a new array, never transforms. */
    object BitPerfectBypass {
        fun copy(input: FloatArray): FloatArray = input.copyOf()

        fun copyInto(input: FloatArray, output: FloatArray) {
            require(output.size >= input.size) { "Output must hold ${input.size} samples" }
            input.copyInto(output, endIndex = input.size)
        }
    }

    /**
     * Brick-wall ceiling used by tests to model the limiter guarantee:
     * output never exceeds 0 dBFS. Returns a new array; input untouched.
     */
    object JvmLimiter {
        const val CEILING_LINEAR = 1.0f

        fun limit(samples: FloatArray, ceiling: Float = CEILING_LINEAR): FloatArray {
            require(ceiling.isFinite() && ceiling > 0f) { "ceiling must be finite and positive" }
            return FloatArray(samples.size) { index ->
                val value = samples[index]
                if (!value.isFinite()) 0f else value.coerceIn(-ceiling, ceiling)
            }
        }
    }
}
