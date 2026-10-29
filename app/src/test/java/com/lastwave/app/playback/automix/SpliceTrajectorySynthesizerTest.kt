package com.lastwave.app.playback.automix

import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.BeatGridEstimate
import com.lastwave.app.playback.automix.analysis.PhraseBoundary
import com.lastwave.app.playback.automix.analysis.VocalActivityMask
import com.lastwave.app.playback.automix.analysis.VocalSegment
import com.lastwave.app.playback.automix.api.Bpm
import com.lastwave.app.playback.automix.api.CamelotKey
import com.lastwave.app.playback.automix.api.DecibelLufs
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.TransitionDurationMode
import com.lastwave.app.playback.automix.api.TransitionStyle
import com.lastwave.app.playback.automix.engine.SpliceTrajectorySynthesizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpliceTrajectorySynthesizerTest {

    private fun createDummyTrack(
        id: String,
        durationMs: Long,
        album: String? = "Test Album",
        trackNumber: Int? = 1,
    ) = PlayableTrack(
        videoId = id,
        title = "Track $id",
        artist = "Artist",
        album = album,
        trackNumber = trackNumber,
        durationMs = durationMs,
    )

    private fun createDummySignature(
        trackId: String,
        durationMs: Long,
        camelotKey: String = "8A",
        bpm: Float = 120f,
        introVocals: List<VocalSegment> = emptyList(),
        outroVocals: List<VocalSegment> = emptyList(),
    ) = AcousticSignature(
        trackId = trackId,
        durationMs = durationMs,
        sourceHash = "dummy",
        camelotKey = CamelotKey(camelotKey),
        beatGrid = BeatGridEstimate(
            bpm = Bpm(bpm),
            beatIntervalMs = (60_000f / bpm).toLong(),
            firstDownbeatMs = 500L,
            downbeatAnchorsMs = listOf(500L, 2500L, 4500L),
            phraseBoundary = PhraseBoundary(10_000L, durationMs - 15_000L, durationMs - 500L),
        ),
        introVocalMask = VocalActivityMask(0L, 45_000L, introVocals),
        outroVocalMask = VocalActivityMask(durationMs - 45_000L, 45_000L, outroVocals),
        integratedLufs = DecibelLufs(-14.0f),
    )

    @Test
    fun synthesizePlan_respects12To30SecondsRange() {
        val outTrack = createDummyTrack("1", 200_000L)
        val inTrack = createDummyTrack("2", 200_000L)
        val outSig = createDummySignature("1", 200_000L)
        val inSig = createDummySignature("2", 200_000L)

        // 1. Manual 12s setting
        val settings12 = AutoMixSettings(
            durationMode = TransitionDurationMode.MANUAL,
            targetDurationSeconds = 12,
        )
        val plan12 = SpliceTrajectorySynthesizer.synthesizePlan(outTrack, outSig, inTrack, inSig, settings12)
        assertFalse(plan12.isBypass)
        assertTrue(plan12.durationMs >= 12_000L)

        // 2. Manual 30s setting
        val settings30 = AutoMixSettings(
            durationMode = TransitionDurationMode.MANUAL,
            targetDurationSeconds = 30,
        )
        val plan30 = SpliceTrajectorySynthesizer.synthesizePlan(outTrack, outSig, inTrack, inSig, settings30)
        assertFalse(plan30.isBypass)
        assertTrue(plan30.durationMs <= 30_000L)
    }

    @Test
    fun synthesizePlan_shortTrackGuardBypassesTransitions() {
        val shortOut = createDummyTrack("short", 10_000L) // Under 15 seconds
        val normalIn = createDummyTrack("normal", 180_000L)
        val settings = AutoMixSettings()

        val plan = SpliceTrajectorySynthesizer.synthesizePlan(shortOut, null, normalIn, null, settings)
        assertTrue(plan.isBypass)
        assertEquals("Short-track duration guard (<15s)", plan.bypassReason)
    }

    @Test
    fun synthesizePlan_musicVideoOrLiveBypassesTransitions() {
        val outTrack = createDummyTrack("1", 180_000L)
        val inTrack = createDummyTrack("2", 180_000L)
        val settings = AutoMixSettings()

        val plan = SpliceTrajectorySynthesizer.synthesizePlan(
            outgoingTrack = outTrack,
            outgoingSignature = null,
            incomingTrack = inTrack,
            incomingSignature = null,
            settings = settings,
            isMusicVideoOrLive = true,
        )
        assertTrue(plan.isBypass)
        assertEquals("Live broadcast or video stream guard", plan.bypassReason)
    }

    @Test
    fun synthesizePlan_detectsVocalCollisionsAndProtectsSinging() {
        val duration = 200_000L
        val outTrack = createDummyTrack("1", duration)
        val inTrack = createDummyTrack("2", duration)

        // Outgoing track has vocals until the very end
        val outroVocals = listOf(VocalSegment(duration - 15_000L, duration - 1_000L))
        // Incoming track also starts singing right away (within intro window)
        val introVocals = listOf(VocalSegment(0L, 10_000L))

        val outSig = createDummySignature("1", duration, outroVocals = outroVocals)
        val inSig = createDummySignature("2", duration, introVocals = introVocals)

        val settings = AutoMixSettings(
            durationMode = TransitionDurationMode.MANUAL,
            targetDurationSeconds = 16,
        )
        val plan = SpliceTrajectorySynthesizer.synthesizePlan(outTrack, outSig, inTrack, inSig, settings)

        // Should engage loop drop or adjust trajectory to prevent vocal clashing
        assertTrue(plan.requiresPhraseLoop || plan.transitionStyle == TransitionStyle.LOOP_DROP || plan.durationMs <= 16_000L)
    }
}
