package com.lastwave.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualitySwitchTraceTest {
    private var nowMs = 10_000L

    private fun trace() = QualitySwitchTrace(trackTitle = "Song", requestedQuality = 6) { nowMs }

    @Test
    fun `stage durations use monotonic marks`() {
        val trace = trace()

        nowMs += 10L
        trace.markResolveStart()
        nowMs += 100L
        trace.markResolveEnd()
        nowMs += 50L
        trace.markValidationStart()
        nowMs += 500L
        trace.markValidationEnd()
        nowMs += 20L
        trace.markProbeStart()
        nowMs += 1_500L
        trace.markProbeEnd(lagMs = 25L, confidence = 0.82, cacheHit = false)
        nowMs += 30L
        trace.markAlignmentStart()
        nowMs += 700L
        trace.markAlignmentEnd(rounds = 2, driftMs = 4L)
        nowMs += 1L
        trace.markFadeStart()
        nowMs += 240L
        nowMs += 10L

        val report = trace.finish("swapped")

        requireNotNull(report)
        assertEquals(10L, report.gateMs)
        assertEquals(100L, report.resolveMs)
        assertEquals(500L, report.validationMs)
        assertEquals(1_500L, report.probeMs)
        assertEquals(700L, report.alignmentMs)
        assertEquals(2_801L, report.resolveToSwitchMs)
        assertEquals(3_161L, report.totalMs)
        assertEquals(25L, report.lagMs)
        assertEquals(0.82, report.offsetConfidence!!, 0.0001)
        assertEquals(false, report.offsetCacheHit)
        assertEquals(2, report.alignRounds)
        assertEquals(4L, report.driftMs)
        assertTrue(report.logLine().contains("resolveToSwitchMs=2801"))
    }

    @Test
    fun `cache hit is reported without a confidence`() {
        val trace = trace()
        trace.markResolveStart()
        trace.markResolveEnd()
        trace.markProbeStart()
        trace.markProbeEnd(lagMs = -120L, confidence = null, cacheHit = true)

        val report = trace.finish("swapped")!!

        assertTrue(report.offsetCacheHit)
        assertNull(report.offsetConfidence)
        assertEquals(-120L, report.lagMs)
    }

    @Test
    fun `finish only produces one report`() {
        val trace = trace()
        trace.markResolveStart()
        trace.markResolveEnd()

        requireNotNull(trace.finish("swapped"))
        assertNull(trace.finish("swapped"))
    }

    @Test
    fun `missing stages are absent rather than zero`() {
        val trace = trace()
        nowMs += 20L

        val report = trace.finish("no-candidate")!!

        assertNull(report.resolveMs)
        assertNull(report.validationMs)
        assertNull(report.probeMs)
        assertNull(report.resolveToSwitchMs)
        assertEquals(20L, report.totalMs)
        assertEquals("no-candidate", report.outcome)
    }
}
