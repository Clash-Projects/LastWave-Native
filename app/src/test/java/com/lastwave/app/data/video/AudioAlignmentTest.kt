package com.lastwave.app.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class AudioAlignmentTest {

    private val hopsPerSecond = 1_000 / ALIGNMENT_HOP_MS

    private fun noise(seconds: Int, seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(seconds * hopsPerSecond) { random.nextFloat() }
    }

    @Test
    fun findsIntroInTheVideo() {
        val song = noise(60, seed = 1)
        val video = noise(7, seed = 2) + song
        assertEquals(7_000L, estimateVideoOffsetMs(song, video))
    }

    @Test
    fun findsSongIntroMissingFromTheVideo() {
        val song = noise(60, seed = 1)
        val video = song.copyOfRange(3 * hopsPerSecond, song.size)
        assertEquals(-3_000L, estimateVideoOffsetMs(song, video))
    }

    @Test
    fun findsNoOffsetWhenBothMatch() {
        val song = noise(60, seed = 1)
        assertEquals(0L, estimateVideoOffsetMs(song, song.copyOf()))
    }

    @Test
    fun toleratesADifferentMaster() {
        val song = noise(60, seed = 1)
        val random = Random(3)
        val video = noise(5, seed = 2) + FloatArray(song.size) { song[it] * 0.6f + random.nextFloat() * 0.25f }
        assertEquals(5_000L, estimateVideoOffsetMs(song, video))
    }

    @Test
    fun rejectsUnrelatedAudio() {
        assertNull(estimateVideoOffsetMs(noise(60, seed = 1), noise(60, seed = 2)))
    }

    @Test
    fun rejectsPerfectlyRepetitiveAudioBecauseEveryRepeatMatches() {
        val bar = noise(2, seed = 1)
        val song = FloatArray(60 * hopsPerSecond) { bar[it % bar.size] }
        assertNull(estimateVideoOffsetMs(song, song.copyOf()))
    }

    @Test
    fun rejectsAudioTooShortToCompare() {
        assertNull(estimateVideoOffsetMs(noise(5, seed = 1), noise(60, seed = 2)))
    }

    @Test
    fun consistentEstimateAcceptsAVideoWithOneOffsetThroughout() {
        val song = noise(60, seed = 1)
        val video = noise(7, seed = 2) + song
        assertEquals(7_000L, estimateConsistentVideoOffsetMs(song, video))
    }

    @Test
    fun consistentEstimateAcceptsAVideoThatReplacesTheSongOpening() {
        val song = noise(60, seed = 1)
        val video = noise(25, seed = 2) + song.copyOfRange(30 * hopsPerSecond, song.size)
        assertEquals(-5_000L, estimateConsistentVideoOffsetMs(song, video))
    }

    @Test
    fun consistentEstimateRejectsUnrelatedAudio() {
        assertNull(estimateConsistentVideoOffsetMs(noise(60, seed = 1), noise(100, seed = 2)))
    }

    @Test
    fun consistentEstimateRejectsAVideoReEditedPartway() {
        val song = noise(60, seed = 1)
        val middle = song.size / 2
        val video = song.copyOfRange(0, middle) + noise(9, seed = 2) + song.copyOfRange(middle, song.size)
        assertNull(estimateConsistentVideoOffsetMs(song, video))
    }

    @Test
    fun onsetsOnlyCountRisesInLoudness() {
        val onsets = onsetEnvelope(floatArrayOf(0f, 1f, 0.5f))
        assertEquals(0f, onsets[0])
        assertEquals(true, onsets[1] > 0f)
        assertEquals(0f, onsets[2])
    }
}
