package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoMixScheduleTest {

    private fun base(
        durationMs: Long = 200_000L,
        positionMs: Long = 185_000L, // 15s remaining <= 30s clamp
        barMode: Int = 8,
        bpm: Float = 120f,
        automixEnabled: Boolean = true,
        bitPerfectEnabled: Boolean = false,
        sequentialAlbumBypass: Boolean = false,
        isPlaying: Boolean = true,
        repeatOne: Boolean = false,
        hasNextItem: Boolean = true,
        nextItemResolved: Boolean = true,
        streamExpired: Boolean = false,
        standbyArmedForNext: Boolean = true,
        standbyReady: Boolean = true,
        standbyDurationMs: Long = 200_000L,
        standbyBufferedMs: Long = 15_000L,
    ) = AutoMixPlanInput(
        automixEnabled = automixEnabled,
        bitPerfectEnabled = bitPerfectEnabled,
        sequentialAlbumBypass = sequentialAlbumBypass,
        isPlaying = isPlaying,
        repeatOne = repeatOne,
        durationMs = durationMs,
        positionMs = positionMs,
        barMode = barMode,
        bpm = bpm,
        hasNextItem = hasNextItem,
        nextItemResolved = nextItemResolved,
        streamExpired = streamExpired,
        standbyArmedForNext = standbyArmedForNext,
        standbyReady = standbyReady,
        standbyDurationMs = standbyDurationMs,
        standbyBufferedMs = standbyBufferedMs,
    )

    @Test
    fun resolvesImmediatelyAtTrackStart() {
        val plan = planAutoMix(
            base(positionMs = 1000L, nextItemResolved = false),
        )
        assertThat(plan).isEqualTo(AutoMixDecision.ResolveNext(AutoMixResolveReason.Placeholder))
    }

    @Test
    fun armsStandbyWhenResolvedEvenOutsideClamped30sWindow() {
        val plan = planAutoMix(
            base(positionMs = 1000L, standbyArmedForNext = false),
        )
        assertThat(plan).isEqualTo(AutoMixDecision.Arm)
    }

    @Test
    fun waitsForReadyIfBufferedBelow8000Ms() {
        val plan = planAutoMix(
            base(
                positionMs = 185_000L,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyBufferedMs = 7_999L,
            ),
        )
        assertThat(plan).isEqualTo(AutoMixDecision.WaitingForReady)
    }

    @Test
    fun handsOffWhenArmedReadyAndBufferedAtLeast8000Ms() {
        val plan = planAutoMix(
            base(
                positionMs = 185_000L,
                standbyArmedForNext = true,
                standbyReady = true,
                standbyBufferedMs = 8_000L,
            ),
        )
        assertThat(plan).isInstanceOf(AutoMixDecision.HandOff::class.java)
    }

    @Test
    fun idlesOutside30sClampWhenArmedAndReady() {
        val plan = planAutoMix(
            base(
                durationMs = 200_000L,
                positionMs = 150_000L, // 50s remaining > 30s
                standbyArmedForNext = true,
                standbyReady = true,
                standbyBufferedMs = 20_000L,
            ),
        )
        assertThat(plan).isEqualTo(AutoMixDecision.Idle(AutoMixIdleReason.OutsideClamped30sWindow))
    }
}
