package com.lastwave.app.playback.automix

import com.lastwave.app.playback.automix.analysis.BeatGrid
import com.lastwave.app.playback.automix.engine.KickTap
import com.lastwave.app.playback.automix.engine.KickTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class KickTapTest {

    private fun feed(tap: KickTap, pcm: FloatArray, rate: Int, startUs: Long) {
        val chunk = rate / 100
        var offset = 0
        while (offset < pcm.size) {
            val size = minOf(chunk, pcm.size - offset)
            val buffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(pcm, offset, size)
            tap.process(buffer, 1, rate, startUs + offset * 1_000_000L / rate)
            offset += size
        }
    }

    @Test
    fun findsKicksInPlaybackTime() {
        val tap = KickTap().apply { enabled = true }
        // Kicks on beats 1 and 3 at 124 bpm, first downbeat 0.2 s, window starting at media 90 s.
        feed(tap, AutoMixSignals.song(124.0, 10.0, firstDownbeatS = 0.2, padLevel = 0.03), AutoMixSignals.RATE, 90_000_000L)
        val onsets = tap.onsetsSince(0L)
        assertTrue("found ${onsets.size}", onsets.size >= 8)
        val beat = 60_000.0 / 124
        for (us in onsets) {
            val ms = us / 1000.0 - 90_200.0
            val phase = ((ms % beat) + beat) % beat
            assertTrue("onset ${ms}ms is ${minOf(phase, beat - phase)}ms off the beat", minOf(phase, beat - phase) <= 15.0)
        }
    }

    @Test
    fun disabledTapRecordsNothing() {
        val tap = KickTap()
        feed(tap, AutoMixSignals.song(120.0, 4.0), AutoMixSignals.RATE, 0L)
        assertEquals(0, tap.onsetsSince(0L).size)
    }

    @Test
    fun offsetIsTheMedianDistanceToTheGrid() {
        val grid = BeatGrid(bpm = 120f, downbeatMs = 1_000.0, confidence = 1f, barConfidence = 1f)
        val kicks = longArrayOf(1_012_000, 1_511_000, 2_013_000, 2_510_000, 3_012_000)
        val offset = KickTiming.offsetMs(kicks, grid)!!
        assertTrue("offset $offset", abs(offset - 12.0) <= 1.0)
        // Scattered onsets are not evidence of a steady kick.
        assertNull(KickTiming.offsetMs(longArrayOf(1_000_000, 1_620_000, 2_130_000, 2_740_000, 3_010_000), grid))
        assertNull(KickTiming.offsetMs(longArrayOf(1_000_000, 1_500_000), grid))
    }
}
