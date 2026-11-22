package com.lastwave.app.data.recognition

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

class PcmResamplerTest {

    private fun tone(freq: Double, rate: Int, seconds: Int) =
        ShortArray(rate * seconds) { (10_000 * sin(2 * PI * freq * it / rate)).toInt().toShort() }

    private fun peak(s: ShortArray) = s.drop(200).dropLast(200).maxOf { abs(it.toInt()) }

    @Test
    fun downsampledLengthMatchesRatio() {
        val out = PcmResampler.resample(tone(440.0, 44_100, 2), 44_100, 16_000)
        assertThat(out.size).isAtLeast(31_998)
        assertThat(out.size).isAtMost(32_000)
    }

    @Test
    fun passesInBandTone() {
        val out = PcmResampler.resample(tone(1_000.0, 44_100, 1), 44_100, 16_000)
        assertThat(peak(out)).isGreaterThan(9_000)
    }

    @Test
    fun suppressesToneAboveOutputNyquist() {
        val out = PcmResampler.resample(tone(15_000.0, 44_100, 1), 44_100, 16_000)
        assertThat(peak(out)).isLessThan(500)
    }

    @Test
    fun sameRateIsIdentity() {
        val input = tone(440.0, 16_000, 1)
        assertThat(PcmResampler.resample(input, 16_000, 16_000)).isSameInstanceAs(input)
    }
}
