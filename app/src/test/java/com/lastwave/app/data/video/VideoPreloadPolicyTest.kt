package com.lastwave.app.data.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreloadPolicyTest {

    private val now = 100L * 24 * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun preloadsAfterRecentVideoUse() {
        assertTrue(shouldPreloadVideo(now - day, now, batterySaverOn = false))
        assertTrue(shouldPreloadVideo(now - 7 * day, now, batterySaverOn = false))
    }

    @Test
    fun skipsWhenVideoWasNeverUsed() {
        assertFalse(shouldPreloadVideo(null, now, batterySaverOn = false))
    }

    @Test
    fun skipsAfterSevenDaysWithoutVideo() {
        assertFalse(shouldPreloadVideo(now - 7 * day - 1, now, batterySaverOn = false))
    }

    @Test
    fun skipsInBatterySaver() {
        assertFalse(shouldPreloadVideo(now - day, now, batterySaverOn = true))
    }

    @Test
    fun skipsWhenTheClockMovedBackwards() {
        assertFalse(shouldPreloadVideo(now + day, now, batterySaverOn = false))
    }
}
