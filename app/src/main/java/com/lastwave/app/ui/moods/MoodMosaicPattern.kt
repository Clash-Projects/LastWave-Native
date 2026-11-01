package com.lastwave.app.ui.moods

internal const val MOOD_MIN_CELL_DP = 88
internal const val MOOD_TILE_GAP_DP = 8

internal data class MoodTilePlacement(
    val index: Int,
    val startDp: Int,
    val topDp: Int,
    val widthDp: Int,
    val heightDp: Int,
)

private data class MoodGridTile(
    val column: Int,
    val row: Int,
    val columns: Int,
    val rows: Int,
)

/** Complete three-row blocks keep the mixed shapes aligned without interior gaps. */
internal fun moodMosaicPattern(
    tileCount: Int,
    cellSizeDp: Int = MOOD_MIN_CELL_DP,
    cellHeightDp: Int = MOOD_MIN_CELL_DP,
): List<MoodTilePlacement> {
    require(tileCount >= 0)
    require(cellSizeDp > 0 && cellHeightDp > 0)
    return moodHorizontalGrid(tileCount).toPlacements(cellSizeDp, cellHeightDp)
}

/** Uses complete bands in reading order and a compact final band on every viewport. */
internal fun moodMosaicVerticalPattern(
    tileCount: Int,
    availableWidthDp: Int,
    cellHeightDp: Int = MOOD_MIN_CELL_DP,
): List<MoodTilePlacement> {
    require(tileCount >= 0)
    require(cellHeightDp > 0)
    val minimumCellSizeDp = maxOf(MOOD_MIN_CELL_DP, cellHeightDp)
    val columns = moodMosaicColumnCount(availableWidthDp, minimumCellSizeDp)
    val cellSizeDp = moodMosaicCellSize(availableWidthDp, minimumCellSizeDp)
    val rowHeightDp = minimumCellSizeDp
    if (columns == 1) {
        return List(tileCount) { MoodGridTile(0, it, 1, 1) }.toPlacements(cellSizeDp, rowHeightDp)
    }
    val grid = buildList {
        var remaining = tileCount
        var band = 0
        while (remaining >= columns * 2) {
            var column = 0
            var block = band
            while (column < columns) {
                val remainingColumns = columns - column
                val blockColumns = when (remainingColumns) {
                    1 -> 1
                    2, 4 -> 2
                    else -> 3
                }
                addBlock(moodBlock(blockColumns * 2, block), column, band * 3)
                column += blockColumns
                block++
            }
            remaining -= columns * 2
            band++
        }
        addBlock(moodVerticalTail(remaining, columns, band), 0, band * 3)
    }
    return grid.toPlacements(cellSizeDp, rowHeightDp)
}

internal fun moodMosaicColumnCount(
    availableWidthDp: Int,
    minimumCellSizeDp: Int = MOOD_MIN_CELL_DP,
): Int {
    require(minimumCellSizeDp > 0)
    return ((availableWidthDp.coerceAtLeast(1) + MOOD_TILE_GAP_DP) /
        (minimumCellSizeDp + MOOD_TILE_GAP_DP)).coerceAtLeast(1)
}

internal fun moodMosaicCellSize(
    availableWidthDp: Int,
    minimumCellSizeDp: Int = MOOD_MIN_CELL_DP,
): Int {
    val widthDp = availableWidthDp.coerceAtLeast(1)
    val columns = moodMosaicColumnCount(widthDp, minimumCellSizeDp)
    return (widthDp - (columns - 1) * MOOD_TILE_GAP_DP) / columns
}

private fun moodHorizontalGrid(tileCount: Int, firstBlock: Int = 0): List<MoodGridTile> = buildList {
    var remaining = tileCount
    var column = 0
    var block = firstBlock
    while (remaining > 0) {
        val count = minOf(remaining, 6)
        val tiles = moodBlock(count, block)
        addBlock(tiles, column, 0)
        column += tiles.maxOf { it.column + it.columns }
        remaining -= count
        block++
    }
}

private fun moodVerticalTail(tileCount: Int, columns: Int, firstBlock: Int): List<MoodGridTile> = buildList {
    var remaining = tileCount
    var column = 0
    var block = firstBlock
    while (remaining > 3) {
        val count = minOf(remaining, 6)
        val tiles = moodBlock(count, block)
        addBlock(tiles, column, 0)
        column += tiles.maxOf { it.column + it.columns }
        remaining -= count
        block++
    }
    val remainingColumns = columns - column
    val tail = when (remaining) {
        1 -> listOf(MoodGridTile(0, 0, 1, 1))
        2 -> if (remainingColumns >= 3) {
            listOf(MoodGridTile(0, 0, 2, 1), MoodGridTile(2, 0, 1, 1))
        } else {
            listOf(MoodGridTile(0, 0, 1, 1), MoodGridTile(1, 0, 1, 1))
        }
        3 -> if (remainingColumns >= 3) {
            listOf(MoodGridTile(0, 0, 1, 1), MoodGridTile(1, 0, 1, 1), MoodGridTile(2, 0, 1, 1))
        } else {
            listOf(MoodGridTile(0, 0, 2, 1), MoodGridTile(0, 1, 1, 1), MoodGridTile(1, 1, 1, 1))
        }
        else -> emptyList()
    }
    addBlock(tail, column, 0)
}

private fun MutableList<MoodGridTile>.addBlock(
    tiles: List<MoodGridTile>,
    column: Int,
    row: Int,
) {
    tiles.forEach { add(it.copy(column = it.column + column, row = it.row + row)) }
}

private fun moodBlock(tileCount: Int, variation: Int): List<MoodGridTile> = when (tileCount) {
    1 -> listOf(MoodGridTile(0, 0, 1, 1))
    2 -> if (variation % 2 == 0) {
        listOf(MoodGridTile(0, 0, 1, 2), MoodGridTile(0, 2, 1, 1))
    } else {
        listOf(MoodGridTile(0, 0, 1, 1), MoodGridTile(0, 1, 1, 2))
    }
    3 -> listOf(
        MoodGridTile(0, 0, 2, 1),
        MoodGridTile(0, 1, 1, 2), MoodGridTile(1, 1, 1, 2),
    )
    4 -> {
        val tallColumn = variation % 2
        listOf(
            MoodGridTile(0, 0, 2, 1),
            MoodGridTile(tallColumn, 1, 1, 2),
            MoodGridTile(1 - tallColumn, 1, 1, 1),
            MoodGridTile(1 - tallColumn, 2, 1, 1),
        )
    }
    5 -> listOf(
        MoodGridTile(0, 0, 1, 2), MoodGridTile(1, 0, 2, 1),
        MoodGridTile(1, 1, 2, 1),
        MoodGridTile(0, 2, 2, 1), MoodGridTile(2, 2, 1, 1),
    )
    6 -> if (variation % 2 == 0) {
        listOf(
            MoodGridTile(0, 0, 2, 1), MoodGridTile(2, 0, 1, 2),
            MoodGridTile(0, 1, 1, 1), MoodGridTile(1, 1, 1, 1),
            MoodGridTile(0, 2, 2, 1), MoodGridTile(2, 2, 1, 1),
        )
    } else {
        listOf(
            MoodGridTile(0, 0, 1, 1), MoodGridTile(1, 0, 2, 1),
            MoodGridTile(0, 1, 1, 2), MoodGridTile(1, 1, 1, 1), MoodGridTile(2, 1, 1, 2),
            MoodGridTile(1, 2, 1, 1),
        )
    }
    else -> emptyList()
}

private fun List<MoodGridTile>.toPlacements(
    cellSizeDp: Int,
    cellHeightDp: Int,
): List<MoodTilePlacement> = sortedWith(compareBy<MoodGridTile> { it.row }.thenBy { it.column })
    .mapIndexed { index, tile ->
        MoodTilePlacement(
            index = index,
            startDp = tile.column * (cellSizeDp + MOOD_TILE_GAP_DP),
            topDp = tile.row * (cellHeightDp + MOOD_TILE_GAP_DP),
            widthDp = tile.columns * cellSizeDp + (tile.columns - 1) * MOOD_TILE_GAP_DP,
            heightDp = tile.rows * cellHeightDp + (tile.rows - 1) * MOOD_TILE_GAP_DP,
        )
    }
