package com.lastwave.app.ui.moods

internal const val MOOD_TILE_HEIGHT_DP = 64
internal const val MOOD_TILE_SQUARE_WIDTH_DP = 64
internal const val MOOD_TILE_WIDE_WIDTH_DP = 136
internal const val MOOD_TILE_GAP_DP = 8

internal data class MoodTilePlacement(
    val index: Int,
    val row: Int,
    val startDp: Int,
    val widthDp: Int,
) {
    val topDp: Int get() = row * (MOOD_TILE_HEIGHT_DP + MOOD_TILE_GAP_DP)
}

/** Keeps source order across the first row, then the second, with a fixed staggered pattern. */
internal fun moodMosaicPattern(tileCount: Int): List<MoodTilePlacement> {
    require(tileCount >= 0)
    val firstRowCount = (tileCount + 1) / 2
    return buildList(tileCount) {
        repeat(2) { row ->
            val count = if (row == 0) firstRowCount else tileCount - firstRowCount
            var startDp = 0
            repeat(count) { column ->
                val widthDp = if ((column + row) % 2 == 0) {
                    MOOD_TILE_WIDE_WIDTH_DP
                } else {
                    MOOD_TILE_SQUARE_WIDTH_DP
                }
                add(MoodTilePlacement(size, row, startDp, widthDp))
                startDp += widthDp + MOOD_TILE_GAP_DP
            }
        }
    }
}

/** Wraps the same tile widths in reading order, keeping complete tiles inside each row. */
internal fun moodMosaicVerticalPattern(tileCount: Int, availableWidthDp: Int): List<MoodTilePlacement> {
    val widthLimit = availableWidthDp.coerceAtLeast(MOOD_TILE_WIDE_WIDTH_DP)
    val tiles = moodMosaicPattern(tileCount)
    var row = 0
    var startDp = 0
    return tiles.map { tile ->
        if (startDp > 0 && startDp + tile.widthDp > widthLimit) {
            row++
            startDp = 0
        }
        tile.copy(row = row, startDp = startDp).also {
            startDp += tile.widthDp + MOOD_TILE_GAP_DP
        }
    }
}
