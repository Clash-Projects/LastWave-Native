package com.lastwave.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SwapAlignmentTest {
    private fun music(size: Int, seed: Int): FloatArray {
        val random = Random(seed)
        var value = 0f
        return FloatArray(size) {
            // Smoothed noise: broadband like music, with no repeating period.
            value = value * 0.95f + (random.nextFloat() - 0.5f) * 0.2f
            value
        }
    }

    @Test
    fun `finds how far the candidate runs ahead`() {
        val source = music(SwapAlignment.CAPTURE_BINS + 16_000, seed = 1)
        val lagBins = 1_000 // 125 ms
        val reference = source.copyOfRange(8_000, 8_000 + SwapAlignment.CAPTURE_BINS)
        val candidate = source.copyOfRange(8_000 + lagBins * -1, 8_000 - lagBins + SwapAlignment.CAPTURE_BINS)

        val lag = SwapAlignment.measureLagMs(SwapCapture(reference, 0L), SwapCapture(candidate, 0L))

        assertEquals(125L, lag)
    }

    @Test
    fun `includes the difference in first timestamps`() {
        val source = music(SwapAlignment.CAPTURE_BINS, seed = 2)

        val lag = SwapAlignment.measureLagMs(SwapCapture(source, 0L), SwapCapture(source, 20_000L))

        assertEquals(20L, lag)
    }

    @Test
    fun `a real match reports its confidence for the offset cache`() {
        val source = music(SwapAlignment.CAPTURE_BINS, seed = 5)

        val match = SwapAlignment.measure(SwapCapture(source, 0L), SwapCapture(source, 0L))

        assertNotNull(match)
        assertEquals(0L, match!!.lagMs)
        assertTrue(match.confidence >= SwapOffsetCache.MIN_CACHE_CONFIDENCE)
    }

    @Test
    fun `different songs give no offset`() {
        val first = music(SwapAlignment.CAPTURE_BINS, seed = 3)
        val second = music(SwapAlignment.CAPTURE_BINS, seed = 4)

        assertNull(SwapAlignment.measureLagMs(SwapCapture(first, 0L), SwapCapture(second, 0L)))
    }

    @Test
    fun `silence gives no offset`() {
        val silence = FloatArray(SwapAlignment.CAPTURE_BINS)

        assertNull(SwapAlignment.measureLagMs(SwapCapture(silence, 0L), SwapCapture(silence, 0L)))
    }

    @Test
    fun `different audio is a verdict, not a missing measurement`() {
        val first = music(SwapAlignment.CAPTURE_BINS, seed = 3)
        val second = music(SwapAlignment.CAPTURE_BINS, seed = 4)

        assertTrue(SwapAlignment.compare(SwapCapture(first, 0L), SwapCapture(second, 0L)) is SwapAlignmentVerdict.Different)
    }

    @Test
    fun `silence or far-apart captures cannot judge the candidate`() {
        val silence = FloatArray(SwapAlignment.CAPTURE_BINS)
        val source = music(SwapAlignment.CAPTURE_BINS, seed = 6)

        assertTrue(SwapAlignment.compare(SwapCapture(silence, 0L), SwapCapture(source, 0L)) is SwapAlignmentVerdict.Unknown)
        assertTrue(SwapAlignment.compare(SwapCapture(source, 0L), SwapCapture(source, 20_000_000L)) is SwapAlignmentVerdict.Unknown)
    }

    @Test
    fun `the same audio is judged the same`() {
        val source = music(SwapAlignment.CAPTURE_BINS, seed = 7)

        assertTrue(SwapAlignment.compare(SwapCapture(source, 0L), SwapCapture(source, 0L)) is SwapAlignmentVerdict.Same)
    }
}
