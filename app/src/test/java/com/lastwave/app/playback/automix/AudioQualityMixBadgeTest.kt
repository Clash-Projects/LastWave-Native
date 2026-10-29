package com.lastwave.app.playback.automix

import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry
import com.lastwave.app.playback.automix.api.NormalizedFloat
import com.lastwave.app.playback.automix.api.TransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQualityMixBadgeTest {

    @Test
    fun telemetry_isBlendingActiveOnlyDuringBlendAndLoopPhases() {
        val idle = DeckBlendTelemetry(phase = BlendPhase.IDLE)
        assertFalse(idle.isBlendingActive)

        val preparing = DeckBlendTelemetry(phase = BlendPhase.PRE_ANALYZING)
        assertFalse(preparing.isBlendingActive)

        val blending = DeckBlendTelemetry(phase = BlendPhase.BLENDING)
        assertTrue(blending.isBlendingActive)

        val loopLocked = DeckBlendTelemetry(phase = BlendPhase.LOOP_LOCKED)
        assertTrue(loopLocked.isBlendingActive)

        val completed = DeckBlendTelemetry(phase = BlendPhase.MIX_COMPLETED)
        assertFalse(completed.isBlendingActive)
    }

    @Test
    fun telemetry_clampedProgressConstrainedToZeroOneRange() {
        val negative = DeckBlendTelemetry(blendProgress = -0.25f)
        assertEquals(0.0f, negative.clampedProgress, 0.0001f)

        val normal = DeckBlendTelemetry(blendProgress = 0.65f)
        assertEquals(0.65f, normal.clampedProgress, 0.0001f)

        val overflow = DeckBlendTelemetry(blendProgress = 1.45f)
        assertEquals(1.0f, overflow.clampedProgress, 0.0001f)
    }

    @Test
    fun telemetry_formatsBpmAndHarmonicKeyCorrectly() {
        val telemetry = DeckBlendTelemetry(
            phase = BlendPhase.BLENDING,
            syncedBpm = 126.4f,
            harmonicKeyPair = "8A → 9A",
            transitionStyle = TransitionStyle.HARMONIC_BEATMIX,
        )

        assertEquals("8A → 9A", telemetry.harmonicKeyPair)
        assertEquals(126.4f, telemetry.syncedBpm ?: 0f, 0.01f)
        assertEquals(TransitionStyle.HARMONIC_BEATMIX, telemetry.transitionStyle)
    }
}
