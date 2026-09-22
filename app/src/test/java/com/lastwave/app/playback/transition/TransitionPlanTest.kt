package com.lastwave.app.playback.transition

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [TransitionPlan] data model invariants and validation logic.
 */
class TransitionPlanTest {

    @Test
    fun fallbackPlanHasCorrectDefaults() {
        val plan = TransitionPlan.fallback(durationMs = 5000L)
        assertThat(plan.type).isEqualTo(TransitionType.FALLBACK_CROSSFADE)
        assertThat(plan.durationMs).isEqualTo(5000L)
        assertThat(plan.silenceGapMs).isEqualTo(0L)
        assertThat(plan.outgoingBassCutDb).isEqualTo(0f)
        assertThat(plan.isFallback).isTrue()
        assertThat(plan.hookStartMs).isNull()
        assertThat(plan.hookEndMs).isNull()
    }

    @Test
    fun fallbackPlanCoercesNegativeDuration() {
        val plan = TransitionPlan.fallback(durationMs = -1000L)
        assertThat(plan.durationMs).isEqualTo(0L)
        assertThat(plan.isFallback).isTrue()
    }

    @Test
    fun negativeDurationThrowsException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TransitionPlan(
                type = TransitionType.SMOOTH_BLEND,
                durationMs = -500L
            )
        }
        assertThat(exception.message).contains("durationMs cannot be negative")
    }

    @Test
    fun negativeSilenceGapThrowsException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TransitionPlan(
                type = TransitionType.IMPACT_DROP,
                durationMs = 150L,
                silenceGapMs = -100L
            )
        }
        assertThat(exception.message).contains("silenceGapMs cannot be negative")
    }

    @Test
    fun positiveBassCutThrowsException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TransitionPlan(
                type = TransitionType.SMOOTH_BLEND,
                durationMs = 3000L,
                outgoingBassCutDb = 3.0f // Boost is forbidden; must be attenuation
            )
        }
        assertThat(exception.message).contains("outgoingBassCutDb must be non-positive")
    }

    @Test
    fun validPlanConstructsCleanly() {
        val plan = TransitionPlan(
            type = TransitionType.IMPACT_DROP,
            durationMs = 150L,
            silenceGapMs = 400L,
            outgoingBassCutDb = 0f,
            isFallback = false
        )
        assertThat(plan.type).isEqualTo(TransitionType.IMPACT_DROP)
        assertThat(plan.durationMs).isEqualTo(150L)
        assertThat(plan.silenceGapMs).isEqualTo(400L)
        assertThat(plan.isFallback).isFalse()
    }
}
