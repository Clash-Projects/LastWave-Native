package com.lastwave.app.data.recognition

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sample-rate conversion for mono 16-bit PCM using a Hann-windowed sinc kernel.
 * The kernel low-passes below the output Nyquist so content above it cannot
 * fold back into the band the fingerprint reads.
 */
object PcmResampler {

    private const val HALF_WIDTH = 24 // input samples either side of the output position
    private const val CUTOFF_MARGIN = 0.92

    fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        if (inRate == outRate || input.isEmpty()) return input

        val step = inRate.toDouble() / outRate
        // Cutoff in cycles per input sample; only narrows when downsampling.
        val cutoff = 0.5 * minOf(1.0, outRate.toDouble() / inRate) * CUTOFF_MARGIN
        val outCount = (input.size / step).toInt()
        val out = ShortArray(outCount)

        for (n in 0 until outCount) {
            val centre = n * step
            val first = (centre.toInt() - HALF_WIDTH + 1).coerceAtLeast(0)
            val last = (centre.toInt() + HALF_WIDTH).coerceAtMost(input.size - 1)
            var acc = 0.0
            for (i in first..last) {
                val d = i - centre
                acc += input[i] * kernel(d, cutoff)
            }
            out[n] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun kernel(d: Double, cutoff: Double): Double {
        if (abs(d) >= HALF_WIDTH) return 0.0
        val window = 0.5 * (1.0 + cos(PI * d / HALF_WIDTH))
        val x = 2.0 * cutoff * d
        val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
        return 2.0 * cutoff * sinc * window
    }
}
