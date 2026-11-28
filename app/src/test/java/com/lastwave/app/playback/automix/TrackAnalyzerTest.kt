package com.lastwave.app.playback.automix

import com.lastwave.app.playback.automix.AutoMixSignals.RATE
import com.lastwave.app.playback.automix.analysis.EndingStyle
import com.lastwave.app.playback.automix.analysis.IntroStyle
import com.lastwave.app.playback.automix.analysis.TrackAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TrackAnalyzerTest {

    @Test
    fun steadyBeat_recoversTempoBarPhaseAndConfidence() {
        val firstDownbeat = 0.37
        val analysis = TrackAnalyzer.analyze(AutoMixSignals.song(124.0, 30.0, firstDownbeat), RATE, 0.0)
        val grid = requireNotNull(analysis.grid) { "no grid" }
        assertEquals(124.0, grid.bpm.toDouble(), 124.0 * 0.01)
        assertTrue("confidence ${grid.confidence}", grid.confidence >= 0.6f)
        // The fitted downbeat must sit on a true bar start (any whole bar away).
        val barMs = 4 * 60_000.0 / 124.0
        val offset = ((grid.downbeatMs - firstDownbeat * 1000) % barMs + barMs) % barMs
        val error = minOf(offset, barMs - offset)
        assertTrue("downbeat off by ${error}ms", error <= 30.0)
    }

    @Test
    fun timestampsFollowTheWindowStart() {
        val analysis = TrackAnalyzer.analyze(AutoMixSignals.song(120.0, 30.0, 0.0), RATE, 200_000.0)
        val grid = analysis.grid!!
        val offset = ((grid.downbeatMs - 200_000.0) % grid.barMs + grid.barMs) % grid.barMs
        assertTrue("downbeat off by ${minOf(offset, grid.barMs - offset)}ms", minOf(offset, grid.barMs - offset) <= 30.0)
        assertTrue(analysis.startMs == 200_000L)
    }

    @Test
    fun noise_isNotTrustedAsABeat() {
        val analysis = TrackAnalyzer.analyze(AutoMixSignals.noise(30.0), RATE, 0.0)
        val confidence = analysis.grid?.confidence ?: 0f
        assertTrue("noise confidence $confidence", confidence < 0.6f)
    }

    @Test
    fun diatonicPad_landsOnCamelotEight() {
        val analysis = TrackAnalyzer.analyze(AutoMixSignals.song(120.0, 30.0, padLevel = 0.3), RATE, 0.0)
        val key = requireNotNull(analysis.camelotKey) { "no key" }
        assertEquals("key $key", "8", key.dropLast(1))
    }

    @Test
    fun silence_isTrimmedFromBothEnds() {
        val pcm = AutoMixSignals.song(120.0, 25.0, leadSilenceS = 2.0, tailSilenceS = 3.0)
        val analysis = TrackAnalyzer.analyze(pcm, RATE, 0.0)
        assertTrue("start ${analysis.audibleStartMs}", abs(analysis.audibleStartMs!! - 2_000L) <= 60L)
        assertTrue("end ${analysis.audibleEndMs}", abs(analysis.audibleEndMs!! - 27_000L) <= 300L)
    }

    @Test
    fun endings_areClassified() {
        val fading = TrackAnalyzer.analyze(AutoMixSignals.song(120.0, 30.0, fadeOutS = 10.0), RATE, 0.0)
        assertEquals(EndingStyle.FADE_OUT, fading.ending)
        assertTrue("fade start ${fading.fadeStartMs}", abs(fading.fadeStartMs!! - 20_000L) <= 2_500L)

        val cold = TrackAnalyzer.analyze(AutoMixSignals.song(120.0, 28.0, tailSilenceS = 2.0), RATE, 0.0)
        assertEquals(EndingStyle.COLD, cold.ending)
        assertEquals(IntroStyle.COLD, cold.intro)
    }

    @Test
    fun sectionChange_isAPhraseStart() {
        val barS = 4 * 60.0 / 124
        val quiet = AutoMixSignals.song(124.0, 16 * barS, seed = 8)
        val loud = AutoMixSignals.song(124.0, 16 * barS, seed = 9)
        val pcm = FloatArray(quiet.size + loud.size)
        for (i in quiet.indices) pcm[i] = quiet[i] * 0.3f
        loud.copyInto(pcm, quiet.size)
        val analysis = TrackAnalyzer.analyze(pcm, RATE, 0.0)
        val boundary = quiet.size * 1000L / RATE
        val mark = analysis.phrases.maxByOrNull { it.shiftDb }
        assertTrue("phrases ${analysis.phrases}", mark != null && abs(mark.ms - boundary) <= 150 && mark.shiftDb >= 5f)
    }

    @Test
    fun bands_coverTheWindow() {
        val analysis = TrackAnalyzer.analyze(AutoMixSignals.song(120.0, 20.0), RATE, 0.0)
        assertTrue("columns ${analysis.bands.size}", abs(analysis.bands.size - 200) <= 2)
        assertTrue(analysis.bands.any { (it shr 16) and 0xFF > 150 })
    }
}
