package com.lastwave.app.playback.automix

import com.lastwave.app.playback.MediaType
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.BeatGrid
import com.lastwave.app.playback.automix.analysis.EndingStyle
import com.lastwave.app.playback.automix.analysis.IntroStyle
import com.lastwave.app.playback.automix.analysis.PhraseMark
import com.lastwave.app.playback.automix.analysis.VocalSpan
import com.lastwave.app.playback.automix.analysis.WindowAnalysis
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.TransitionDurationMode
import com.lastwave.app.playback.automix.api.TransitionStyle
import com.lastwave.app.playback.automix.engine.MixBypass
import com.lastwave.app.playback.automix.engine.MixDecision
import com.lastwave.app.playback.automix.engine.MixPlan
import com.lastwave.app.playback.automix.api.MixShape
import com.lastwave.app.playback.automix.engine.PlanInput
import com.lastwave.app.playback.automix.engine.TransitionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TransitionPlannerTest {
    private val outDuration = 200_000L
    private val inDuration = 210_000L

    private fun track(id: String, album: String? = null, number: Int? = null, type: MediaType = MediaType.SONG) =
        PlayableTrack(title = id, artist = "Artist", album = album, videoId = id, trackNumber = number, mediaType = type)

    private fun window(
        start: Long,
        end: Long,
        bpm: Float? = null,
        downbeat: Double = start + 120.0,
        confidence: Float = 0.9f,
        key: String? = null,
        audibleStart: Long? = start,
        audibleEnd: Long? = end,
        ending: EndingStyle = EndingStyle.NATURAL,
        fadeStart: Long? = null,
        intro: IntroStyle = IntroStyle.BUILD,
    ) = WindowAnalysis(
        startMs = start,
        endMs = end,
        grid = bpm?.let { BeatGrid(it, downbeat, confidence, 0.8f) },
        camelotKey = key,
        keyConfidence = if (key != null) 0.9f else 0f,
        audibleStartMs = audibleStart,
        audibleEndMs = audibleEnd,
        ending = ending,
        fadeStartMs = fadeStart,
        intro = intro,
    )

    private fun outSig(outro: WindowAnalysis, vocals: List<VocalSpan>? = null) =
        AcousticSignature(key = "out", durationMs = outDuration, intro = null, outro = outro, vocals = vocals)

    private fun inSig(intro: WindowAnalysis, vocals: List<VocalSpan>? = null) =
        AcousticSignature(key = "in", durationMs = inDuration, intro = intro, outro = null, vocals = vocals)

    private fun plan(
        out: AcousticSignature?,
        incoming: AcousticSignature?,
        settings: AutoMixSettings = AutoMixSettings(autoMixEnabled = true),
        speed: Float = 1f,
        outTrack: PlayableTrack = track("a"),
        inTrack: PlayableTrack = track("b"),
        outgoingDuration: Long = outDuration,
    ): MixDecision = TransitionPlanner.plan(
        PlanInput(outTrack, inTrack, outgoingDuration, inDuration, out, incoming, settings, speed, 1f),
    )

    private val beatOutro = window(160_000, 200_000, bpm = 124f, audibleEnd = 197_000)
    private val beatIntro = window(0, 40_000, bpm = 126f, downbeat = 450.0, audibleStart = 300)

    @Test
    fun bypasses_shortTracksPodcastsAndAlbumSequences() {
        assertTrue(plan(null, null, outgoingDuration = 20_000) is MixBypass)
        assertTrue(plan(null, null, inTrack = track("b", type = MediaType.PODCAST_EPISODE)) is MixBypass)
        assertTrue(plan(null, null, outTrack = track("a", "LP", 3), inTrack = track("b", "LP", 4)) is MixBypass)
        // Same album out of order is a playlist choice, not the artist's sequence.
        assertTrue(plan(null, null, outTrack = track("a", "LP", 3), inTrack = track("b", "LP", 7)) is MixPlan)
    }

    @Test
    fun unknownAnalysis_isAPlainFadeAtTheEnd() {
        val plan = plan(null, null) as MixPlan
        assertEquals(MixShape.BASS_SWAP, plan.shape)
        assertFalse(plan.beatLocked)
        assertEquals(outDuration, plan.endOutMs)
        assertEquals(0L, plan.cueInMs)
        assertNull(plan.bpm)
        assertEquals(1f, plan.tempoRatio)
    }

    @Test
    fun silence_isSkippedOnBothSides() {
        val plan = plan(
            outSig(window(160_000, 200_000, audibleEnd = 194_500)),
            inSig(window(0, 40_000, audibleStart = 1_800)),
        ) as MixPlan
        assertEquals(194_500L, plan.endOutMs)
        assertEquals(1_800L, plan.cueInMs)
    }

    @Test
    fun confidentCompatibleGrids_beatmixOnDownbeats() {
        val plan = plan(outSig(beatOutro), inSig(beatIntro)) as MixPlan
        assertTrue(plan.beatLocked)
        assertEquals(TransitionStyle.HARMONIC_BEATMIX, plan.style)
        assertEquals(MixShape.BASS_SWAP, plan.shape)
        assertEquals(124f / 126f, plan.tempoRatio, 1e-4f)
        val outBar = 4 * 60_000.0 / 124
        val inBar = 4 * 60_000.0 / 126
        assertEquals(0.0, ((plan.cueOutMs - beatOutro.grid!!.downbeatMs) / outBar).let { it - Math.round(it) }, 0.01)
        assertEquals(0.0, ((plan.cueInMs - 450.0) / inBar).let { it - Math.round(it) }, 0.01)
        assertTrue(plan.cueInMs >= 300)
        assertTrue("mix must end before the audible end", plan.endOutMs <= 197_000)
        assertEquals(8 * outBar, plan.overlapMs.toDouble(), 2.0)
        // The bass moves on the middle downbeat.
        assertEquals(plan.cueOutMs + 4 * outBar, plan.bassSwapAtMs!!.toDouble(), 2.0)
        // One incoming bar per outgoing bar while locked.
        assertEquals(inBar, (plan.incomingTimeFor((plan.cueOutMs + outBar).toLong()) - plan.cueInMs).toDouble(), 3.0)
    }

    @Test
    fun beatmix_needsTheUsersNormalSpeedAndCloseTempos() {
        assertFalse((plan(outSig(beatOutro), inSig(beatIntro), speed = 1.25f) as MixPlan).beatLocked)
        val farIntro = window(0, 40_000, bpm = 140f)
        assertFalse((plan(outSig(beatOutro), inSig(farIntro)) as MixPlan).beatLocked)
        val weakIntro = window(0, 40_000, bpm = 126f, confidence = 0.3f)
        assertFalse((plan(outSig(beatOutro), inSig(weakIntro)) as MixPlan).beatLocked)
    }

    @Test
    fun halfTime_locksWithoutStretching() {
        val halfIntro = window(0, 40_000, bpm = 62f)
        val plan = plan(outSig(beatOutro), inSig(halfIntro)) as MixPlan
        assertTrue(plan.beatLocked)
        assertEquals(1f, plan.tempoRatio, 1e-4f)
    }

    @Test
    fun singingOverSinging_isAvoided() {
        val outVocals = listOf(VocalSpan(150_000, 197_000))
        val inVocals = listOf(VocalSpan(0, 30_000))
        val plan = plan(outSig(beatOutro, outVocals), inSig(beatIntro, inVocals)) as MixPlan
        assertFalse(plan.beatLocked)
        assertTrue("clash fade ${plan.overlapMs}", plan.overlapMs <= 4_000L)
        assertFalse(plan.vocalSafe)
    }

    @Test
    fun endingShape_choosesTheFade() {
        val fadeOut = plan(
            outSig(window(160_000, 200_000, audibleEnd = 199_000, ending = EndingStyle.FADE_OUT, fadeStart = 189_000)),
            inSig(window(0, 40_000)),
        ) as MixPlan
        assertEquals(10_000L, fadeOut.overlapMs)
        assertEquals(189_000L, fadeOut.cueOutMs)

        val cold = plan(
            outSig(window(160_000, 200_000, audibleEnd = 199_000, ending = EndingStyle.COLD)),
            inSig(window(0, 40_000)),
        ) as MixPlan
        assertEquals(MixShape.HOLD_OUTGOING, cold.shape)
        assertEquals(2_000L, cold.overlapMs)
        assertEquals(199_000L, cold.endOutMs)
    }

    @Test
    fun manualLength_isRespectedAndCapped() {
        val manual = AutoMixSettings(autoMixEnabled = true, durationMode = TransitionDurationMode.MANUAL, targetDurationSeconds = 20)
        assertEquals(20_000L, (plan(null, null, manual) as MixPlan).overlapMs)
        val longOnShort = plan(null, null, manual.copy(targetDurationSeconds = 30), outgoingDuration = 50_000) as MixPlan
        assertEquals(20_000L, longOnShort.overlapMs)
        // A manual beatmix rounds to whole phrases.
        val bars = (plan(outSig(beatOutro), inSig(beatIntro), manual.copy(targetDurationSeconds = 30)) as MixPlan).overlapMs / (4 * 60_000.0 / 124)
        assertTrue("bars $bars", abs(bars - 16) < 0.01)
    }

    @Test
    fun bassSwap_followsTheSetting() {
        val off = AutoMixSettings(autoMixEnabled = true, spectralFilterSweepsEnabled = false)
        assertNull((plan(outSig(beatOutro), inSig(beatIntro), off) as MixPlan).bassSwapAtMs)
        assertNull((plan(null, null, off) as MixPlan).bassSwapAtMs)
        assertEquals(MixShape.EQUAL_POWER, (plan(null, null, off) as MixPlan).shape)
        assertEquals(TransitionStyle.SPECTRAL_SWEEP, (plan(null, null) as MixPlan).style)
    }

    @Test
    fun clashingKeys_keepTheOverlapShort() {
        val plan = plan(
            outSig(window(160_000, 200_000, bpm = 124f, key = "8A", audibleEnd = 197_000)),
            inSig(window(0, 40_000, bpm = 126f, downbeat = 450.0, key = "3B")),
        ) as MixPlan
        assertTrue(plan.beatLocked)
        assertEquals(4 * 4 * 60_000.0 / 124, plan.overlapMs.toDouble(), 2.0)
        assertEquals("8A → 3B", plan.keyPair)
    }

    @Test
    fun beatmix_startsOnAPhraseWhenOneIsClose() {
        val bar = 4 * 60_000.0 / 124
        val latest = 160_120 + 11 * bar
        val phraseStart = latest - 2 * bar
        val outro = beatOutro.copy(phrases = listOf(PhraseMark(phraseStart.toLong(), 6f)))
        val plan = plan(outSig(outro), inSig(beatIntro)) as MixPlan
        assertEquals(phraseStart, plan.cueOutMs.toDouble(), 1.0)
        assertEquals(8, plan.bars)
        // Too far back to be worth the lost outro: the latest bar wins.
        val far = beatOutro.copy(phrases = listOf(PhraseMark((latest - 6 * bar).toLong(), 6f)))
        assertEquals(latest, (plan(outSig(far), inSig(beatIntro)) as MixPlan).cueOutMs.toDouble(), 1.0)
    }
}
