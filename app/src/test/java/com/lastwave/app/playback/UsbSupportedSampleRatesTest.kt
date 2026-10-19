package com.lastwave.app.playback

import com.decent.usbaudio.supportedStandardRatesInRange
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsbSupportedSampleRatesTest {
    @Test
    fun uac1ContinuousFormatRangeIncludes44100And48000() {
        val rates = supportedStandardRatesInRange(8_000, 192_000, 0)

        assertThat(rates).containsAtLeast(44_100, 48_000)
    }

    @Test
    fun uac2ContinuousClockRangeIncludes44100And48000() {
        val rates = supportedStandardRatesInRange(8_000, 384_000, 0)

        assertThat(rates).containsAtLeast(44_100, 48_000)
    }

    @Test
    fun uac2DiscreteResolutionIncludesOnlyAlignedRatesWithinRange() {
        val rates = supportedStandardRatesInRange(8_000, 96_000, 100)

        assertThat(rates).containsAtLeast(44_100, 48_000, 88_200, 96_000)
        assertThat(rates).doesNotContain(176_400)
    }

    @Test
    fun malformedRangeDoesNotAdvertiseRates() {
        assertThat(supportedStandardRatesInRange(96_000, 8_000, 0)).isEmpty()
    }
}
