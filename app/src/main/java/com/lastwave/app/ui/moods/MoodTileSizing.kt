package com.lastwave.app.ui.moods

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.floor

internal const val MOOD_TILE_LABEL_PADDING_DP = 8

/** Reserves enough room for every label even in the narrow square and tall tiles. */
internal fun moodTileMinimumSize(
    labels: List<String>,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    density: Density,
    textWidthDp: Int = MOOD_MIN_CELL_DP - MOOD_TILE_LABEL_PADDING_DP * 2,
): Int {
    // Leave two pixels for rounding when the tile and its padding are converted separately.
    val textWidthPx = (floor(textWidthDp * density.density).toInt() - 2).coerceAtLeast(1)
    val textHeightPx = labels.maxOfOrNull { label ->
        textMeasurer.measure(
            text = label,
            style = textStyle,
            constraints = Constraints(maxWidth = textWidthPx),
        ).size.height
    } ?: 0
    val paddingPx = ceil(MOOD_TILE_LABEL_PADDING_DP * density.density).toInt()
    return maxOf(MOOD_MIN_CELL_DP, ceil((textHeightPx + paddingPx * 2) / density.density).toInt())
}
