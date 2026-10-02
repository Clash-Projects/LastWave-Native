package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The crossfade schedule is the difference between a blend and a hard cut, and
 * every gate here used to be silent. Each test pins one arming/hand-off rule.
 */
class CrossfadeScheduleTest {

    /** 4-minute FLAC track, 5s crossfade, next item already resolved and alive. */
    private fun base(
        positionMs: Long = 0L,
        durationMs: Long = 240_000L,
        crossfadeMs: Long = 5_000L,
        playbackSpeed: Float = 1f,
        crossfadeEnabled: Boolean = true,
        bitPerfectEnabled: Boolean = false,
        isPlaying: Boolean = true,
        repeatOne: Boolean = false,
        hasNextItem: Boolean = true,
        nextItemResolved: Boolean = true,
        streamExpired: Boolean = false,
        standbyArmedForNext: Boolean = false,
        standbyReady: Boolean = false,
        standbyDurationMs: Long = 0L,
        standbyBufferedMs: Long = DEFAULT_BUFFERED_MS,
        armLeadMs: Long = 10_000L,
        refreshLeadMs: Long = 30_000L,
    ) = CrossfadePlanInput(
        crossfadeEnabled = crossfadeEnabled,
        bitPerfectEnabled = bitPerfectEnabled,
        isPlaying = isPlaying,
        repeatOne = repeatOne,
        durationMs = durationMs,
        positionMs = positionMs,
        crossfadeMs = crossfadeMs,
        playbackSpeed = playbackSpeed,
        hasNextItem = hasNextItem,
        nextItemResolved = nextItemResolved,
        streamExpired = streamExpired,
        standbyArmedForNext = standbyArmedForNext,
        standbyReady = standbyReady,
        standbyDurationMs = standbyDurationMs,
        standbyBufferedMs = standbyBufferedMs,
        armLeadMs = armLeadMs,
        refreshLeadMs = refreshLeadMs,
    )

    private companion object {
        /**
         * Standby LoadControl never reports READY below bufferForPlaybackMs
         * (1.5s), so this is the floor in practice. Generous enough that only the
         * explicit buffer-cap tests are actually constrained by it.
         */
        const val DEFAULT_BUFFERED_MS = 30_000L
    }

    // --- the regression this whole change exists for -----------------------------

    @Test
    fun resolvedNextTrackArmsAtTrackStartNotAtTheWindow() {
        // The bug: arming only began inside fadeMs + armLead, so a standby
        // created at T-15s had to resolve, prepare and buffer inside it.
        val plan = planCrossfade(base(positionMs = 2_000L))

        assertThat(plan).isEqualTo(CrossfadeDecision.Arm)
    }

    @Test
    fun armsForTheWholeTrackOnceTheItemIsResolved() {
        // 3 minutes from the end: far outside any arm window, still armed.
        assertThat(planCrossfade(base(positionMs = 3_000L))).isEqualTo(CrossfadeDecision.Arm)

        // Already armed and buffered: hold, do not re-prepare every tick.
        assertThat(
            planCrossfade(
                base(positionMs = 3_000L, standbyArmedForNext = true, standbyReady = true),
            ),
        ).isEqualTo(CrossfadeDecision.WaitingForWindow)
    }

    @Test
    fun moduleStreamThatDiedBeforeTheWindowIsRefreshedWithHeadroomNotAtTheEdge() {
        // Short-TTL provider-module FLAC: 4 minutes left, URL already expired.
        // The refresh window is fade + armLead + refreshLead = 45s, so this is
        // still TooEarly and must not churn the module.
        val early = planCrossfade(
            base(positionMs = 0L, streamExpired = true),
        )
        assertThat(early).isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.TooEarly))

        // 45s out: re-request now, well before the 10s arm lead.
        val inWindow = planCrossfade(
            base(positionMs = 195_000L, streamExpired = true),
        )
        assertThat(inWindow).isEqualTo(CrossfadeDecision.ResolveNext(CrossfadeResolveReason.ExpiredSignature))
    }

    @Test
    fun unresolvedNextItemIsRequestedEarlyRatherThanAtTheWindowEdge() {
        val plan = planCrossfade(
            base(positionMs = 195_000L, nextItemResolved = false),
        )

        assertThat(plan).isEqualTo(CrossfadeDecision.ResolveNext(CrossfadeResolveReason.Placeholder))
    }

    @Test
    fun expiredStreamBlocksTheHandoffEvenWhenTheStandbyIsReady() {
        // Ordering guarantee: an armed, READY standby must not be handed off on
        // a dead signed URL - it would stall mid-track instead of blending.
        val plan = planCrossfade(
            base(
                positionMs = 239_000L,
                streamExpired = true,
                standbyArmedForNext = true,
                standbyReady = true,
            ),
        )

        assertThat(plan).isEqualTo(CrossfadeDecision.ResolveNext(CrossfadeResolveReason.ExpiredSignature))
    }

    // --- hand-off gating ---------------------------------------------------------

    @Test
    fun handOffOnlyInsideTheFadeWindow() {
        val armed = { positionMs: Long ->
            base(
                positionMs = positionMs,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyDurationMs = 240_000L,
            )
        }

        // 10s left with a 5s fade: still outside the window.
        assertThat(planCrossfade(armed(230_000L))).isEqualTo(CrossfadeDecision.WaitingForWindow)
        // 2s left: inside it.
        assertThat(planCrossfade(armed(238_000L)))
            .isEqualTo(CrossfadeDecision.HandOff(overlapMs = 2_000L))
    }

    @Test
    fun bufferingStandbyNeverHandsOff() {
        val plan = planCrossfade(
            base(positionMs = 239_000L, standbyArmedForNext = true, standbyReady = false),
        )

        assertThat(plan).isEqualTo(CrossfadeDecision.WaitingForReady)
    }

    @Test
    fun overlapNeverExceedsAThirdOfTheIncomingTrack() {
        // A 6s crossfade into a 9s track would otherwise clip the new track's
        // own opening; a third is the cap.
        val plan = planCrossfade(
            base(
                durationMs = 9_000L,
                positionMs = 6_000L,
                crossfadeMs = 6_000L,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyDurationMs = 300_000L,
            ),
        )

        // fadeMs = min(6000, 9000/3=3000) = 3000; remaining = 3000.
        assertThat(plan).isEqualTo(CrossfadeDecision.HandOff(overlapMs = 3_000L))
    }

    @Test
    fun shortIncomingTrackBoundsTheOverlapToItsOwnDuration() {
        val plan = planCrossfade(
            base(
                durationMs = 4_000L,
                positionMs = 3_500L,
                crossfadeMs = 5_000L,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyDurationMs = 300_000L,
            ),
        )

        // fadeMs = min(5000, 1333) = 1333, but only 500ms of track remain.
        assertThat(plan).isEqualTo(CrossfadeDecision.HandOff(overlapMs = 500L))
    }

    @Test
    fun playbackSpeedScalesTheWindow() {
        val at2x = base(
            positionMs = 230_000L,
            playbackSpeed = 2f,
            standbyArmedForNext = true,
            standbyReady = true,
            standbyDurationMs = 240_000L,
        )

        // fadeMs = min(10000, 240000/3) = 10000; remaining = 10000.
        assertThat(planCrossfade(at2x)).isEqualTo(CrossfadeDecision.HandOff(overlapMs = 10_000L))
    }

    // --- the user's setting is a ceiling, not a promise --------------------------

    @Test
    fun fullTwelveSecondCrossfadeIsUsedWhenTheStandbyCanSustainIt() {
        val plan = planCrossfade(
            base(
                // First tick inside a 12s window on a 4-minute track.
                positionMs = 228_000L,
                crossfadeMs = 12_000L,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyDurationMs = 240_000L,
                standbyBufferedMs = 28_000L,
            ),
        )

        // remaining 12s, buffered 28s: nothing constrains the full 12s.
        assertThat(plan).isEqualTo(CrossfadeDecision.HandOff(overlapMs = 12_000L))
    }

    @Test
    fun longCrossfadeShrinksToWhatTheStandbyActuallyBuffered() {
        // 12s asked for, but only 4s is buffered: a 12s blend would drop below
        // the incoming track's rebuffer threshold partway through and cut. A 4s
        // blend completes.
        val plan = planCrossfade(
            base(
                positionMs = 230_000L,
                crossfadeMs = 12_000L,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyDurationMs = 240_000L,
                standbyBufferedMs = 4_000L,
            ),
        )

        assertThat(plan).isEqualTo(CrossfadeDecision.HandOff(overlapMs = 4_000L))
    }

    @Test
    fun bufferCapNeverBeatsTheTrackDurationOrTheFadeWindow() {
        // 500ms of buffer and only 400ms left on the outgoing track: the track
        // wins, so the incoming track is never cut off by its own length.
        val plan = planCrossfade(
            base(
                positionMs = 239_600L,
                crossfadeMs = 12_000L,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyDurationMs = 240_000L,
                standbyBufferedMs = 500L,
            ),
        )

        assertThat(plan).isEqualTo(CrossfadeDecision.HandOff(overlapMs = 400L))
    }

    @Test
    fun unreadableBufferDoesNotProduceADegenerateFade() {
        // Defensive: a READY standby reporting 0 buffered must not yield a 1ms
        // click, so the buffer cap falls back to the configured fade.
        val plan = planCrossfade(
            base(
                positionMs = 239_000L,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyDurationMs = 240_000L,
                standbyBufferedMs = 0L,
            ),
        )

        assertThat(plan).isEqualTo(CrossfadeDecision.HandOff(overlapMs = 1_000L))
    }

    // --- gates that must never arm ----------------------------------------------

    @Test
    fun crossfadeOffNeverArms() {
        assertThat(planCrossfade(base(crossfadeEnabled = false)))
            .isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.Disabled))
    }

    @Test
    fun bitPerfectNeverArms() {
        assertThat(planCrossfade(base(bitPerfectEnabled = true)))
            .isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.BitPerfect))
    }

    @Test
    fun pausedNeverArms() {
        assertThat(planCrossfade(base(isPlaying = false)))
            .isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.Paused))
    }

    @Test
    fun repeatOneNeverArms() {
        assertThat(planCrossfade(base(repeatOne = true)))
            .isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.RepeatOne))
    }

    @Test
    fun unknownDurationWaitsInsteadOfGuessing() {
        assertThat(planCrossfade(base(durationMs = 0L)))
            .isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.UnknownDuration))
    }

    @Test
    fun endOfQueueNeverArms() {
        assertThat(planCrossfade(base(hasNextItem = false)))
            .isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.NoNextItem))
    }

    @Test
    fun positionAtOrPastDurationNeverArms() {
        assertThat(planCrossfade(base(positionMs = 240_000L)))
            .isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.Ended))
        assertThat(planCrossfade(base(positionMs = 245_000L)))
            .isEqualTo(CrossfadeDecision.Idle(CrossfadeIdleReason.Ended))
    }
}