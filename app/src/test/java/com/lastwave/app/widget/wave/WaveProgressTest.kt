package com.lastwave.app.widget.wave

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveProgressTest {
    @Test
    fun fraction_clampsInvalidAndOutOfRangePositions() {
        assertThat(WaveProgress.fraction(50, 100)).isEqualTo(0.5f)
        assertThat(WaveProgress.fraction(-1, 100)).isEqualTo(0f)
        assertThat(WaveProgress.fraction(200, 100)).isEqualTo(1f)
        assertThat(WaveProgress.fraction(20, 0)).isEqualTo(0f)
    }

    @Test
    fun points_areFlatAfterPlayedBoundaryAndJoinAtCenter() {
        val points = WaveProgress.points(widthPx = 100, heightPx = 20, progress = 0.5f, sampleCount = 101)
        assertThat(points[50].y).isWithin(0.001f).of(10f)
        assertThat(points.drop(50).all { it.y == 10f }).isTrue()
        assertThat(points.take(50).any { it.y != 10f }).isTrue()
    }
}
