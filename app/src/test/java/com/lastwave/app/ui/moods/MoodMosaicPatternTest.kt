package com.lastwave.app.ui.moods

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoodMosaicPatternTest {
    @Test
    fun twelveTilesUseTheSameStaggeredPatternEveryTime() {
        val pattern = moodMosaicPattern(12)

        assertThat(pattern).isEqualTo(moodMosaicPattern(12))
        assertThat(pattern.map { it.widthDp }).containsExactly(
            136, 64, 136, 64, 136, 64,
            64, 136, 64, 136, 64, 136,
        ).inOrder()
        assertThat(pattern.filter { it.row == 0 }.last().let { it.startDp + it.widthDp }).isEqualTo(640)
        assertThat(pattern.filter { it.row == 1 }.last().let { it.startDp + it.widthDp }).isEqualTo(640)
    }

    @Test
    fun sourceSequenceFollowsReadingOrderWithoutMissingOrRepeatingTiles() {
        for (count in 0..100) {
            val pattern = moodMosaicPattern(count)

            assertThat(pattern.map { it.index }).containsExactlyElementsIn(0 until count).inOrder()
            assertThat(pattern.sortedWith(compareBy<MoodTilePlacement> { it.row }.thenBy { it.startDp }))
                .isEqualTo(pattern)
            assertThat(pattern.count { it.row == 0 }).isEqualTo((count + 1) / 2)
            assertThat(pattern.count { it.row == 1 }).isEqualTo(count / 2)
        }
    }

    @Test
    fun everyTileHasFixedDimensionsAndEightDpGapsWithoutOverlap() {
        for (count in 1..100) {
            val pattern = moodMosaicPattern(count)

            pattern.forEach {
                assertThat(it.widthDp).isAnyOf(64, 136)
                assertThat(it.topDp).isEqualTo(it.row * 72)
            }
            pattern.groupBy { it.row }.values.forEach { row ->
                assertThat(row.first().startDp).isEqualTo(0)
                row.zipWithNext().forEach { (left, right) ->
                    assertThat(right.startDp - (left.startDp + left.widthDp)).isEqualTo(8)
                }
            }
            if (count > 1) {
                val firstTop = pattern.first { it.row == 0 }.topDp
                val secondTop = pattern.first { it.row == 1 }.topDp
                assertThat(secondTop - (firstTop + MOOD_TILE_HEIGHT_DP)).isEqualTo(8)
            }
        }
        assertThat(MOOD_TILE_HEIGHT_DP).isEqualTo(64)
    }

    @Test
    fun emptySingleAndOddCountsKeepOnlyTheirActualTiles() {
        assertThat(moodMosaicPattern(0)).isEmpty()
        assertThat(moodMosaicPattern(1)).containsExactly(MoodTilePlacement(0, 0, 0, 136))
        assertThat(moodMosaicPattern(3)).containsExactly(
            MoodTilePlacement(0, 0, 0, 136),
            MoodTilePlacement(1, 0, 144, 64),
            MoodTilePlacement(2, 1, 0, 64),
        ).inOrder()
    }

    @Test
    fun verticalLayoutPreservesTheSameWidthsAndSourceOrderAcrossViewports() {
        for (count in 0..100) {
            val horizontal = moodMosaicPattern(count)
            for (viewport in listOf(136, 208, 328, 568, 768, 1368)) {
                val vertical = moodMosaicVerticalPattern(count, viewport)

                assertThat(vertical).isEqualTo(moodMosaicVerticalPattern(count, viewport))
                assertThat(vertical.map { it.index }).containsExactlyElementsIn(0 until count).inOrder()
                assertThat(vertical.map { it.widthDp }).isEqualTo(horizontal.map { it.widthDp })
                assertThat(vertical.sortedWith(compareBy<MoodTilePlacement> { it.row }.thenBy { it.startDp }))
                    .isEqualTo(vertical)
            }
        }
    }

    @Test
    fun verticalLayoutFitsPhoneTabletAndLandscapeWithoutTouchingOrOverlapping() {
        for (viewport in listOf(136, 208, 328, 568, 768, 1368)) {
            val pattern = moodMosaicVerticalPattern(43, viewport)

            pattern.forEach {
                assertThat(it.startDp).isAtLeast(0)
                assertThat(it.startDp + it.widthDp).isAtMost(viewport)
            }
            val rows = pattern.groupBy { it.row }.values.toList()
            rows.forEach { row ->
                assertThat(row.first().startDp).isEqualTo(0)
                row.zipWithNext().forEach { (left, right) ->
                    assertThat(right.startDp - (left.startDp + left.widthDp)).isEqualTo(8)
                }
            }
            rows.zipWithNext().forEach { (upper, lower) ->
                assertThat(lower.first().topDp - (upper.first().topDp + MOOD_TILE_HEIGHT_DP)).isEqualTo(8)
            }
            pattern.forEachIndexed { index, tile ->
                pattern.drop(index + 1).forEach { other ->
                    val separatedHorizontally = tile.startDp + tile.widthDp <= other.startDp ||
                        other.startDp + other.widthDp <= tile.startDp
                    val separatedVertically = tile.topDp + MOOD_TILE_HEIGHT_DP <= other.topDp ||
                        other.topDp + MOOD_TILE_HEIGHT_DP <= tile.topDp
                    assertThat(separatedHorizontally || separatedVertically).isTrue()
                }
            }
        }
    }

    @Test
    fun widerViewportsUseFewerRowsAndNarrowViewportsKeepWholeTiles() {
        val phone = moodMosaicVerticalPattern(12, 328)
        val tablet = moodMosaicVerticalPattern(12, 768)
        val landscape = moodMosaicVerticalPattern(12, 1368)

        assertThat(phone.last().row).isGreaterThan(tablet.last().row)
        assertThat(tablet.last().row).isGreaterThan(landscape.last().row)
        assertThat(landscape.last().row).isEqualTo(0)
        assertThat(moodMosaicVerticalPattern(3, 80)).isEqualTo(moodMosaicVerticalPattern(3, 136))
        assertThat(moodMosaicVerticalPattern(0, 328)).isEmpty()
        assertThat(moodMosaicVerticalPattern(1, 328)).containsExactly(MoodTilePlacement(0, 0, 0, 136))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCountsAreRejected() {
        moodMosaicPattern(-1)
    }
}
