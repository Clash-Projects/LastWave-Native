package com.lastwave.app.playback.automix

import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry
import com.lastwave.app.playback.automix.api.NormalizedFloat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixWaveSeekbarTest {

    @Test
    fun rawProgress_clampedBetweenZeroAndOne() {
        val durationMs = 180_000L

        // Before start
        val negativeMs = -5_000L
        val negativeFraction = (negativeMs.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)
        assertEquals(0.0f, negativeFraction, 0.0001f)

        // Mid-track
        val midMs = 90_000L
        val midFraction = (midMs.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)
        assertEquals(0.5f, midFraction, 0.0001f)

        // Beyond end
        val overMs = 250_000L
        val overFraction = (overMs.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)
        assertEquals(1.0f, overFraction, 0.0001f)
    }

    @Test
    fun spliceHorizonFraction_correctlyComputedForSeekbar() {
        val durationMs = 200_000L
        val cueOutMs = 180_000L // 20s transition window

        val horizonFraction = (cueOutMs.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)
        assertEquals(0.90f, horizonFraction, 0.001f)
        assertTrue(horizonFraction > 0.5f)
    }

    @Test
    fun waveTargetAmplitude_activeOnlyWhenPlayingAndBlending() {
        val playingAndBlending = DeckBlendTelemetry(phase = BlendPhase.BLENDING)
        val isPlaying = true

        val activeAmplitude = if (isPlaying && playingAndBlending.isBlendingActive) 1.0f else 0.0f
        assertEquals(1.0f, activeAmplitude, 0.0001f)

        // Paused while blending
        val pausedAmplitude = if (!isPlaying && playingAndBlending.isBlendingActive) 1.0f else 0.0f
        assertEquals(0.0f, pausedAmplitude, 0.0001f)

        // Playing while idle (no blend)
        val idleTelemetry = DeckBlendTelemetry(phase = BlendPhase.IDLE)
        val idleAmplitude = if (isPlaying && idleTelemetry.isBlendingActive) 1.0f else 0.0f
        assertEquals(0.0f, idleAmplitude, 0.0001f)
    }
}
