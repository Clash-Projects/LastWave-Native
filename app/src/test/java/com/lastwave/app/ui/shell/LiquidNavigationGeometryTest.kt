package com.lastwave.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidNavigationGeometryTest {
    @Test
    fun rawFingerPositionPreservesFractionsAndMotionBeyondEndTabs() {
        assertEquals(-0.5f, liquidNavigationRawPosition(0f, 300f, 3), 0.0001f)
        assertEquals(0f, liquidNavigationRawPosition(50f, 300f, 3), 0.0001f)
        assertEquals(0.5f, liquidNavigationRawPosition(100f, 300f, 3), 0.0001f)
        assertEquals(1.25f, liquidNavigationRawPosition(175f, 300f, 3), 0.0001f)
        assertEquals(2.5f, liquidNavigationRawPosition(300f, 300f, 3), 0.0001f)
    }

    @Test
    fun rubberBandPreservesInteriorPositionAndSoftensSymmetricOverscroll() {
        for (position in listOf(0f, 0.125f, 0.5f, 1.25f, 2f)) {
            assertEquals(position, liquidNavigationRubberBand(position, 3), 0.0001f)
        }
        val beforeFirst = liquidNavigationRubberBand(-1f, 3)
        val afterLast = liquidNavigationRubberBand(3f, 3)
        assertTrue("Overscroll should resist movement without a hard endpoint snap", beforeFirst > -1f && beforeFirst < 0f)
        assertTrue("Overscroll should resist movement without a hard endpoint snap", afterLast > 2f && afterLast < 3f)
        assertEquals(-beforeFirst, afterLast - 2f, 0.0001f)
        assertTrue(liquidNavigationRubberBand(-1000f, 3).isFinite())
        assertTrue(liquidNavigationRubberBand(1000f, 3).isFinite())
    }

    @Test
    fun rawAndRubberBandPositionsHandleUnmeasuredLayoutsSafely() {
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, liquidNavigationRawPosition(10f, width, 3), 0f)
        }
        for (position in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, liquidNavigationRawPosition(position, 300f, 3), 0f)
            assertTrue(liquidNavigationRubberBand(position, 3).isFinite())
        }
        for (count in listOf(0, 1)) {
            assertEquals(0f, liquidNavigationRawPosition(100f, 300f, count), 0f)
            assertEquals(0f, liquidNavigationRubberBand(2f, count), 0f)
        }
    }

    @Test
    fun fingerTracksContinuouslyBetweenTabCentresAndClampsAtEnds() {
        assertEquals(0f, liquidNavigationPosition(-100f, 300f, 3), 0f)
        assertEquals(0f, liquidNavigationPosition(50f, 300f, 3), 0f)
        assertEquals(0.5f, liquidNavigationPosition(100f, 300f, 3), 0f)
        assertEquals(1f, liquidNavigationPosition(150f, 300f, 3), 0f)
        assertEquals(2f, liquidNavigationPosition(250f, 300f, 3), 0f)
        assertEquals(2f, liquidNavigationPosition(500f, 300f, 3), 0f)
    }

    @Test
    fun releaseSelectsNearestTabAcrossBothBoundaries() {
        assertEquals(0, liquidNavigationIndex(0.49f, 3, isRtl = false))
        assertEquals(1, liquidNavigationIndex(0.51f, 3, isRtl = false))
        assertEquals(1, liquidNavigationIndex(1.49f, 3, isRtl = false))
        assertEquals(2, liquidNavigationIndex(1.51f, 3, isRtl = false))
    }

    @Test
    fun rtlUsesLogicalTabOrderWhileFingerPositionStaysPhysical() {
        assertEquals(2, liquidNavigationIndex(0f, 3, isRtl = true))
        assertEquals(1, liquidNavigationIndex(1f, 3, isRtl = true))
        assertEquals(0, liquidNavigationIndex(2f, 3, isRtl = true))
        for (isRtl in listOf(false, true)) {
            for (index in 0..2) {
                assertEquals(
                    index,
                    liquidNavigationIndex(liquidNavigationVisualIndex(index, 3, isRtl), 3, isRtl),
                )
            }
        }
    }

    @Test
    fun resizePreservesRelativeFingerPosition() {
        for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(
                liquidNavigationPosition(300f * fraction, 300f, 3),
                liquidNavigationPosition(900f * fraction, 900f, 3),
                0.0001f,
            )
        }
    }

    @Test
    fun unmeasuredAndDegenerateLayoutsProduceFiniteSafePositions() {
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, liquidNavigationPosition(10f, width, 3), 0f)
        }
        for (x in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, liquidNavigationPosition(x, 300f, 3), 0f)
        }
        for (count in listOf(0, 1)) {
            assertEquals(0f, liquidNavigationPosition(100f, 300f, count), 0f)
            assertEquals(0, liquidNavigationIndex(2f, count, isRtl = true))
            assertEquals(0f, liquidNavigationVisualIndex(2, count, isRtl = true), 0f)
        }
        assertEquals(0, liquidNavigationIndex(-5f, 3, isRtl = false))
        assertEquals(2, liquidNavigationIndex(5f, 3, isRtl = false))
        assertEquals(0f, liquidNavigationVisualIndex(-1, 3, isRtl = false), 0f)
        assertEquals(2f, liquidNavigationVisualIndex(99, 3, isRtl = false), 0f)
    }
}
