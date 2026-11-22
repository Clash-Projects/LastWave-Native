package com.lastwave.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapSettleDetectorTest {
    @Test
    fun `consecutive agreeing readings settle`() {
        val detector = SwapSettleDetector(toleranceMs = 3L, stableReads = 2)

        assertFalse(detector.observe(10L))
        assertFalse(detector.observe(12L))
        assertTrue(detector.observe(11L))
    }

    @Test
    fun `a moving clock never settles`() {
        val detector = SwapSettleDetector(toleranceMs = 3L, stableReads = 2)

        assertFalse(detector.observe(10L))
        assertFalse(detector.observe(20L))
        assertFalse(detector.observe(30L))
        assertFalse(detector.observe(40L))
    }

    @Test
    fun `a change restarts the stable streak`() {
        val detector = SwapSettleDetector(toleranceMs = 2L, stableReads = 2)

        assertFalse(detector.observe(10L))
        assertFalse(detector.observe(11L))
        assertFalse(detector.observe(40L))
        assertFalse(detector.observe(41L))
        assertTrue(detector.observe(42L))
    }
}
