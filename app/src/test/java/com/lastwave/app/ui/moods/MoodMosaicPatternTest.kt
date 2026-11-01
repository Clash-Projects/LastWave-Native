package com.lastwave.app.ui.moods

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoodMosaicPatternTest {
    @Test
    fun twelveHomeTilesFillThreeRowsWithSquaresWideAndTallRectangles() {
        val pattern = moodMosaicPattern(12)

        assertThat(pattern).isEqualTo(moodMosaicPattern(12))
        assertThat(pattern.first()).isEqualTo(MoodTilePlacement(0, 0, 0, 216, 104))
        assertThat(pattern.maxOf { it.startDp + it.widthDp }).isEqualTo(664)
        assertThat(pattern.maxOf { it.topDp + it.heightDp }).isEqualTo(328)
        assertMixedShapes(pattern, 104, 104)
        assertFullRectangle(pattern, 104, 104)
    }

    @Test
    fun everyHomeCountPreservesReadingOrderAndSpacingWithoutInteriorHoles() {
        for (count in 0..100) {
            val pattern = moodMosaicPattern(count)

            assertReadingOrder(pattern, count)
            assertSeparated(pattern)
            assertNoInteriorHoles(pattern, 104, 104)
            pattern.forEach { tile ->
                assertThat(tile.widthDp).isAnyOf(104, 216)
                assertThat(tile.heightDp).isAnyOf(104, 216)
                assertThat(tile.topDp + tile.heightDp).isAtMost(328)
            }
        }
    }

    @Test
    fun completeHomeBlocksHaveNoUnfilledCells() {
        for (count in listOf(2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 24, 96)) {
            assertFullRectangle(moodMosaicPattern(count), 104, 104)
        }
        assertThat(moodMosaicPattern(0)).isEmpty()
        assertThat(moodMosaicPattern(1)).containsExactly(MoodTilePlacement(0, 0, 0, 104, 104))
    }

    @Test
    fun largerLabelCellsKeepTheSameDeterministicShapePattern() {
        val pattern = moodMosaicPattern(12, cellSizeDp = 156, cellHeightDp = 156)

        assertThat(pattern).isEqualTo(moodMosaicPattern(12, 156, 156))
        assertThat(pattern.maxOf { it.topDp + it.heightDp }).isEqualTo(484)
        assertMixedShapes(pattern, 156, 156)
        assertFullRectangle(pattern, 156, 156)
        assertSeparated(pattern)
    }

    @Test
    fun responsiveColumnsRespectTheMinimumCellAndEightDpGap() {
        assertThat(moodMosaicColumnCount(80)).isEqualTo(1)
        assertThat(moodMosaicColumnCount(215)).isEqualTo(1)
        assertThat(moodMosaicColumnCount(216)).isEqualTo(2)
        assertThat(moodMosaicColumnCount(328)).isEqualTo(3)
        assertThat(moodMosaicColumnCount(768)).isEqualTo(6)
        assertThat(moodMosaicColumnCount(1368)).isEqualTo(12)
        assertThat(moodMosaicCellSize(328)).isEqualTo(104)
        assertThat(moodMosaicCellSize(768)).isEqualTo(121)
        assertThat(moodMosaicColumnCount(328, 176)).isEqualTo(1)
        assertThat(moodMosaicCellSize(328, 176)).isEqualTo(328)
        assertThat(moodMosaicCellSize(80)).isEqualTo(80)
    }

    @Test
    fun everyPageCountKeepsSourceOrderAndLeavesOnlyOpenTrailingSpace() {
        for (count in 0..100) {
            val pattern = moodMosaicVerticalPattern(count, 328)

            assertThat(pattern).isEqualTo(moodMosaicVerticalPattern(count, 328))
            assertReadingOrder(pattern, count)
            assertSeparated(pattern)
            assertNoInteriorHoles(pattern, 104, 104)
        }
    }

    @Test
    fun completePageBandsFillPhoneTabletAndLandscapeWidths() {
        for (viewport in listOf(80, 216, 328, 440, 568, 768, 1368)) {
            val columns = moodMosaicColumnCount(viewport)
            val cellSize = moodMosaicCellSize(viewport)
            val rowHeight = maxOf(cellSize, MOOD_MIN_CELL_DP)
            val pattern = moodMosaicVerticalPattern(columns * 4, viewport)

            assertReadingOrder(pattern, columns * 4)
            assertFullRectangle(pattern, cellSize, rowHeight)
            assertSeparated(pattern)
            assertThat(pattern.maxOf { it.startDp + it.widthDp }).isAtMost(viewport)
            val rows = if (columns == 1) 4 else 6
            assertThat(pattern.maxOf { it.topDp + it.heightDp }).isEqualTo(rowHeight * rows + (rows - 1) * 8)
            if (columns > 1) assertMixedShapes(pattern, cellSize, rowHeight)
        }
    }

    @Test
    fun partialBandsFitEveryViewportAndLargerFontCell() {
        for (viewport in listOf(1, 80, 136, 208, 328, 568, 768, 1368)) {
            for (minimumHeight in listOf(104, 156, 176)) {
                val minimumCell = maxOf(MOOD_MIN_CELL_DP, minimumHeight)
                val cellSize = moodMosaicCellSize(viewport, minimumCell)
                val rowHeight = maxOf(cellSize, minimumCell)
                for (count in listOf(0, 1, 2, 3, 4, 5, 7, 11, 13, 23, 43, 100)) {
                    val pattern = moodMosaicVerticalPattern(count, viewport, minimumHeight)

                    assertReadingOrder(pattern, count)
                    assertSeparated(pattern)
                    assertNoInteriorHoles(pattern, cellSize, rowHeight)
                    pattern.forEach {
                        assertThat(it.startDp).isAtLeast(0)
                        assertThat(it.startDp + it.widthDp).isAtMost(viewport)
                        assertThat(it.heightDp).isAtLeast(minimumHeight)
                    }
                }
            }
        }
    }

    @Test
    fun narrowPagesStackCompactCellsWithoutHorizontalOverflow() {
        val pattern = moodMosaicVerticalPattern(5, 80)

        assertThat(pattern.map { it.startDp }).containsExactly(0, 0, 0, 0, 0).inOrder()
        assertThat(pattern.map { it.widthDp }).containsExactly(80, 80, 80, 80, 80).inOrder()
        assertThat(pattern.map { it.heightDp }).containsExactly(104, 104, 104, 104, 104).inOrder()
        assertThat(pattern.map { it.topDp }).containsExactly(0, 112, 224, 336, 448).inOrder()
        assertSeparated(pattern)
    }

    @Test
    fun smallPageTailsUseAvailableColumnsWithoutLargeEmptyStacks() {
        val twoColumnPair = moodMosaicVerticalPattern(2, 216)
        val threeColumnPair = moodMosaicVerticalPattern(2, 328)
        val twoColumnTriple = moodMosaicVerticalPattern(3, 216)
        val threeColumnTriple = moodMosaicVerticalPattern(3, 328)

        assertThat(twoColumnPair.map { it.topDp }).containsExactly(0, 0)
        assertThat(twoColumnPair.map { it.widthDp }).containsExactly(104, 104)
        assertThat(threeColumnPair.map { it.topDp }).containsExactly(0, 0)
        assertThat(threeColumnPair.map { it.widthDp }).containsExactly(216, 104).inOrder()
        assertThat(twoColumnTriple.maxOf { it.topDp + it.heightDp }).isEqualTo(216)
        assertThat(threeColumnTriple.map { it.topDp }).containsExactly(0, 0, 0)
        assertThat(threeColumnTriple.map { it.widthDp }).containsExactly(104, 104, 104)
        assertFullRectangle(twoColumnPair, 104, 104)
        assertFullRectangle(threeColumnPair, 104, 104)
        assertFullRectangle(twoColumnTriple, 104, 104)
        assertFullRectangle(threeColumnTriple, 104, 104)
        val tailAfterBlock = moodMosaicVerticalPattern(9, 568).filter { it.startDp >= 345 }
        assertThat(tailAfterBlock.maxOf { it.topDp + it.heightDp }).isAtMost(222)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeHomeCountsAreRejected() {
        moodMosaicPattern(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePageCountsAreRejected() {
        moodMosaicVerticalPattern(-1, 328)
    }

    private fun assertReadingOrder(pattern: List<MoodTilePlacement>, count: Int) {
        assertThat(pattern.map { it.index }).containsExactlyElementsIn(0 until count).inOrder()
        assertThat(pattern.sortedWith(compareBy<MoodTilePlacement> { it.topDp }.thenBy { it.startDp }))
            .isEqualTo(pattern)
    }

    private fun assertMixedShapes(pattern: List<MoodTilePlacement>, cellWidth: Int, cellHeight: Int) {
        assertThat(pattern.any { it.widthDp == cellWidth && it.heightDp == cellHeight }).isTrue()
        assertThat(pattern.any { it.widthDp == cellWidth * 2 + 8 && it.heightDp == cellHeight }).isTrue()
        assertThat(pattern.any { it.widthDp == cellWidth && it.heightDp == cellHeight * 2 + 8 }).isTrue()
    }

    private fun assertSeparated(pattern: List<MoodTilePlacement>) {
        pattern.forEachIndexed { index, tile ->
            pattern.drop(index + 1).forEach { other ->
                val horizontalGap = maxOf(
                    other.startDp - (tile.startDp + tile.widthDp),
                    tile.startDp - (other.startDp + other.widthDp),
                )
                val verticalGap = maxOf(
                    other.topDp - (tile.topDp + tile.heightDp),
                    tile.topDp - (other.topDp + other.heightDp),
                )
                assertThat(horizontalGap >= 8 || verticalGap >= 8).isTrue()
            }
        }
    }

    private fun occupiedCells(
        pattern: List<MoodTilePlacement>,
        cellWidth: Int,
        cellHeight: Int,
    ): Set<Pair<Int, Int>> = buildSet {
        pattern.forEach { tile ->
            assertThat(tile.startDp % (cellWidth + 8)).isEqualTo(0)
            assertThat(tile.topDp % (cellHeight + 8)).isEqualTo(0)
            val column = tile.startDp / (cellWidth + 8)
            val row = tile.topDp / (cellHeight + 8)
            val columns = (tile.widthDp + 8) / (cellWidth + 8)
            val rows = (tile.heightDp + 8) / (cellHeight + 8)
            assertThat(tile.widthDp + 8).isEqualTo(columns * (cellWidth + 8))
            assertThat(tile.heightDp + 8).isEqualTo(rows * (cellHeight + 8))
            repeat(columns) { x -> repeat(rows) { y -> assertThat(add(column + x to row + y)).isTrue() } }
        }
    }

    private fun assertFullRectangle(pattern: List<MoodTilePlacement>, cellWidth: Int, cellHeight: Int) {
        val cells = occupiedCells(pattern, cellWidth, cellHeight)
        val columns = cells.maxOf { it.first } + 1
        val rows = cells.maxOf { it.second } + 1
        assertThat(cells.size).isEqualTo(columns * rows)
    }

    private fun assertNoInteriorHoles(pattern: List<MoodTilePlacement>, cellWidth: Int, cellHeight: Int) {
        if (pattern.isEmpty()) return
        val cells = occupiedCells(pattern, cellWidth, cellHeight)
        val maxColumn = cells.maxOf { it.first }
        val maxRow = cells.maxOf { it.second }
        val openCells = buildSet {
            for (x in 0..maxColumn) for (y in 0..maxRow) if ((x to y) !in cells) add(x to y)
        }
        val reached = openCells.filterTo(mutableSetOf()) { (x, y) ->
            x == 0 || x == maxColumn || y == 0 || y == maxRow
        }
        val pending = ArrayDeque(reached)
        while (pending.isNotEmpty()) {
            val (x, y) = pending.removeFirst()
            for (neighbor in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (neighbor in openCells && reached.add(neighbor)) pending.addLast(neighbor)
            }
        }
        assertThat(reached).containsExactlyElementsIn(openCells)
    }
}
