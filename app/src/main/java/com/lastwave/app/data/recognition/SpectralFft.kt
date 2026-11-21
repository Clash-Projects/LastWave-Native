package com.lastwave.app.data.recognition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Radix-2 FFT with tables built once per size. [size] must be a power of two. */
internal class SpectralFft(private val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    /** Writes |X[k]|^2 for k in 0..size/2 into [power]; [frame] holds [size] real samples. */
    fun powerSpectrum(frame: DoubleArray, power: DoubleArray) {
        for (i in 0 until size) {
            re[reversed[i]] = frame[i]
            im[reversed[i]] = 0.0
        }
        var span = 1
        while (span < size) {
            val stride = size / (span * 2)
            var start = 0
            while (start < size) {
                for (k in 0 until span) {
                    val wr = cosTable[k * stride]
                    val wi = sinTable[k * stride]
                    val a = start + k
                    val b = a + span
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += span * 2
            }
            span *= 2
        }
        for (k in 0..size / 2) power[k] = re[k] * re[k] + im[k] * im[k]
    }
}
