package com.lastwave.app.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollCollapseTrackerTest {
    private val tracker = ScrollCollapseTracker(thresholdPx = 30f)

    @Test
    fun collapsesOnlyAfterThresholdOfDownwardScroll() {
        assertFalse(tracker.onScroll(consumedY = -20f, availableY = 0f))
        assertTrue(tracker.onScroll(consumedY = -10f, availableY = 0f))
    }

    @Test
    fun jitterInAlternatingDirectionsNeverFlips() {
        repeat(20) {
            assertFalse(tracker.onScroll(consumedY = -25f, availableY = 0f))
            assertFalse(tracker.onScroll(consumedY = 25f, availableY = 0f))
        }
    }

    @Test
    fun expandsAfterThresholdOfUpwardScroll() {
        tracker.onScroll(consumedY = -40f, availableY = 0f)
        assertTrue(tracker.onScroll(consumedY = 20f, availableY = 0f))
        assertFalse(tracker.onScroll(consumedY = 10f, availableY = 0f))
    }

    @Test
    fun reachingTheTopExpandsImmediately() {
        tracker.onScroll(consumedY = -40f, availableY = 0f)
        assertFalse(tracker.onScroll(consumedY = 2f, availableY = 5f))
    }

    @Test
    fun overscrollAtTheEndOfAShortListDoesNotCollapse() {
        assertFalse(tracker.onScroll(consumedY = 0f, availableY = -100f))
    }

    @Test
    fun resetExpandsAndForgetsPartialTravel() {
        tracker.onScroll(consumedY = -40f, availableY = 0f)
        tracker.onScroll(consumedY = -20f, availableY = 0f)
        assertFalse(tracker.reset())
        assertFalse(tracker.onScroll(consumedY = -20f, availableY = 0f))
    }
}
