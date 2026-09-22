package com.lastwave.app.playback.transition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Exhaustive unit tests for [SmartTransitionSelector] rule engine.
 *
 * Verifies all transitions, edge cases, streaming safeguards, and fail-open behaviors.
 */
class SmartTransitionSelectorTest {

    private val defaultCrossfadeMs = 5000L

    @Test
    fun whenDisabled_alwaysReturnsFallback() {
        val outgoing = TrackTransitionMetadata(
            trackId = "track_1",
            durationMs = 200_000L,
            bpm = 120.0f,
            energy = 0.70f,
            loudnessDb = -14.0f
        )
        val incoming = TrackTransitionMetadata(
            trackId = "track_2",
            durationMs = 180_000L,
            bpm = 122.0f,
            energy = 0.72f,
            loudnessDb = -14.0f
        )

        val plan = SmartTransitionSelector.choose(
            outgoing = outgoing,
            incoming = incoming,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = false
        )

        assertThat(plan.type).isEqualTo(TransitionType.FALLBACK_CROSSFADE)
        assertThat(plan.durationMs).isEqualTo(defaultCrossfadeMs)
        assertThat(plan.isFallback).isTrue()
    }

    @Test
    fun whenOutgoingOrIncomingNull_returnsFallback() {
        val track = TrackTransitionMetadata(trackId = "track_1", bpm = 120f, energy = 0.5f)

        val planNullOutgoing = SmartTransitionSelector.choose(
            outgoing = null,
            incoming = track,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )
        assertThat(planNullOutgoing.type).isEqualTo(TransitionType.FALLBACK_CROSSFADE)
        assertThat(planNullOutgoing.isFallback).isTrue()

        val planNullIncoming = SmartTransitionSelector.choose(
            outgoing = track,
            incoming = null,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )
        assertThat(planNullIncoming.type).isEqualTo(TransitionType.FALLBACK_CROSSFADE)
        assertThat(planNullIncoming.isFallback).isTrue()
    }

    @Test
    fun whenMetadataMissing_returnsFallback() {
        val outgoing = TrackTransitionMetadata(trackId = "stream_1")
        val incoming = TrackTransitionMetadata(trackId = "stream_2")

        val plan = SmartTransitionSelector.choose(
            outgoing = outgoing,
            incoming = incoming,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )

        assertThat(plan.type).isEqualTo(TransitionType.FALLBACK_CROSSFADE)
        assertThat(plan.isFallback).isTrue()
    }

    @Test
    fun whenCompatibleTempoAndEnergy_selectsSmoothBlend() {
        val outgoing = TrackTransitionMetadata(
            trackId = "edm_1",
            bpm = 128.0f,
            energy = 0.85f,
            loudnessDb = -12.0f
        )
        val incoming = TrackTransitionMetadata(
            trackId = "edm_2",
            bpm = 130.0f, // diff = 2.0 <= 6.0
            energy = 0.88f, // diff = 0.03 <= 0.20
            loudnessDb = -13.0f // diff = 1.0 <= 3.0
        )

        val plan = SmartTransitionSelector.choose(
            outgoing = outgoing,
            incoming = incoming,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )

        assertThat(plan.type).isEqualTo(TransitionType.SMOOTH_BLEND)
        assertThat(plan.durationMs).isEqualTo(defaultCrossfadeMs)
        assertThat(plan.outgoingBassCutDb).isEqualTo(-8.0f)
        assertThat(plan.silenceGapMs).isEqualTo(0L)
        assertThat(plan.isFallback).isFalse()
    }

    @Test
    fun whenTempoMismatchLarge_selectsImpactDrop() {
        val outgoing = TrackTransitionMetadata(
            trackId = "hiphop_1",
            bpm = 145.0f,
            energy = 0.75f
        )
        val incoming = TrackTransitionMetadata(
            trackId = "ballad_1",
            bpm = 72.0f, // diff = 73.0 >= 12.0
            energy = 0.70f
        )

        val plan = SmartTransitionSelector.choose(
            outgoing = outgoing,
            incoming = incoming,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )

        assertThat(plan.type).isEqualTo(TransitionType.IMPACT_DROP)
        assertThat(plan.durationMs).isEqualTo(SmartTransitionSelector.DEFAULT_IMPACT_DROP_FADE_OUT_MS)
        assertThat(plan.silenceGapMs).isEqualTo(SmartTransitionSelector.DEFAULT_IMPACT_DROP_GAP_MS)
        assertThat(plan.isFallback).isFalse()
    }

    @Test
    fun whenEnergyMismatchLarge_selectsImpactDrop() {
        val outgoing = TrackTransitionMetadata(
            trackId = "rock_1",
            bpm = 120.0f,
            energy = 0.95f
        )
        val incoming = TrackTransitionMetadata(
            trackId = "acoustic_1",
            bpm = 122.0f, // tempo compatible
            energy = 0.30f  // diff = 0.65 >= 0.40
        )

        val plan = SmartTransitionSelector.choose(
            outgoing = outgoing,
            incoming = incoming,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )

        assertThat(plan.type).isEqualTo(TransitionType.IMPACT_DROP)
        assertThat(plan.durationMs).isEqualTo(SmartTransitionSelector.DEFAULT_IMPACT_DROP_FADE_OUT_MS)
        assertThat(plan.silenceGapMs).isEqualTo(SmartTransitionSelector.DEFAULT_IMPACT_DROP_GAP_MS)
    }

    @Test
    fun whenHookAvailableAndSeekable_selectsHookBridge() {
        val outgoing = TrackTransitionMetadata(
            trackId = "pop_hit",
            bpm = 118.0f,
            energy = 0.65f,
            hookStartMs = 45_000L,
            hookEndMs = 48_500L, // 3500ms duration
            isSeekable = true
        )
        val incoming = TrackTransitionMetadata(
            trackId = "pop_hit_2",
            bpm = 120.0f,
            energy = 0.70f // energy diff = 0.05 <= 0.35
        )

        val plan = SmartTransitionSelector.choose(
            outgoing = outgoing,
            incoming = incoming,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )

        assertThat(plan.type).isEqualTo(TransitionType.HOOK_BRIDGE)
        assertThat(plan.durationMs).isEqualTo(3500L)
        assertThat(plan.hookStartMs).isEqualTo(45_000L)
        assertThat(plan.hookEndMs).isEqualTo(48_500L)
        assertThat(plan.outgoingBassCutDb).isEqualTo(-4.0f)
    }

    @Test
    fun whenHookAvailableButNotSeekable_avoidsHookBridge() {
        val outgoing = TrackTransitionMetadata(
            trackId = "stream_hit",
            bpm = 128.0f,
            energy = 0.80f,
            hookStartMs = 45_000L,
            hookEndMs = 48_500L,
            isSeekable = false // Streaming track, cannot guarantee zero-latency backward seek
        )
        val incoming = TrackTransitionMetadata(
            trackId = "stream_hit_2",
            bpm = 128.0f,
            energy = 0.82f
        )

        val plan = SmartTransitionSelector.choose(
            outgoing = outgoing,
            incoming = incoming,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )

        // Must avoid Hook Bridge on unbuffered streaming and fall back to Smooth Blend
        assertThat(plan.type).isNotEqualTo(TransitionType.HOOK_BRIDGE)
        assertThat(plan.type).isEqualTo(TransitionType.SMOOTH_BLEND)
    }

    @Test
    fun whenAmbiguousMetrics_safelyFallsBackToCrossfade() {
        val outgoing = TrackTransitionMetadata(
            trackId = "track_a",
            bpm = 100.0f,
            energy = 0.50f
        )
        val incoming = TrackTransitionMetadata(
            trackId = "track_b",
            bpm = 109.0f, // diff = 9.0 (neither <= 6.0 nor >= 12.0)
            energy = 0.75f  // diff = 0.25 (neither <= 0.20 nor >= 0.40)
        )

        val plan = SmartTransitionSelector.choose(
            outgoing = outgoing,
            incoming = incoming,
            crossfadeDurationMs = defaultCrossfadeMs,
            enabled = true
        )

        assertThat(plan.type).isEqualTo(TransitionType.FALLBACK_CROSSFADE)
        assertThat(plan.isFallback).isTrue()
    }
}
