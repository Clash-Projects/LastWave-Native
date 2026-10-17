package com.lastwave.app.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsButtonsAutoHideTest {

    @Test
    fun nullFallsBackToDefaultFiveSeconds() {
        assertThat(sanitizeLyricsButtonsAutoHideSeconds(null)).isEqualTo(5)
    }

    @Test
    fun zeroMeansNever() {
        assertThat(sanitizeLyricsButtonsAutoHideSeconds(0)).isEqualTo(0)
    }

    @Test
    fun offeredRangePassesThroughUntouched() {
        for (seconds in 2..10) {
            assertThat(sanitizeLyricsButtonsAutoHideSeconds(seconds)).isEqualTo(seconds)
        }
    }

    @Test
    fun belowRangeClampsToTwoSeconds() {
        assertThat(sanitizeLyricsButtonsAutoHideSeconds(1)).isEqualTo(2)
        assertThat(sanitizeLyricsButtonsAutoHideSeconds(-3)).isEqualTo(2)
    }

    @Test
    fun aboveRangeClampsToTenSeconds() {
        assertThat(sanitizeLyricsButtonsAutoHideSeconds(11)).isEqualTo(10)
        assertThat(sanitizeLyricsButtonsAutoHideSeconds(99)).isEqualTo(10)
    }
}
