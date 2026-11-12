package com.lastwave.app.ui.shell

import kotlin.math.roundToInt

/** The lens follows the finger continuously, stopping at the first/last tab centre. */
internal fun liquidNavigationPosition(x: Float, width: Float, itemCount: Int): Float {
    if (itemCount <= 1 || width <= 0f || !x.isFinite() || !width.isFinite()) return 0f
    return (x / (width / itemCount) - 0.5f).coerceIn(0f, (itemCount - 1).toFloat())
}

/** Physical finger position without quantizing to a tab or clamping at its centre. */
internal fun liquidNavigationRawPosition(x: Float, width: Float, itemCount: Int): Float {
    if (itemCount <= 1 || width <= 0f || !x.isFinite() || !width.isFinite()) return 0f
    return x / (width / itemCount) - 0.5f
}

/** Small elastic travel beyond the end tabs, with no magnetic regions between them. */
internal fun liquidNavigationRubberBand(position: Float, itemCount: Int): Float {
    if (itemCount <= 1 || !position.isFinite()) return 0f
    val last = (itemCount - 1).toFloat()
    val limit = 0.18f
    return when {
        position < 0f -> -limit * (1f - 1f / (1f - position / limit))
        position > last -> last + limit * (1f - 1f / (1f + (position - last) / limit))
        else -> position
    }
}

/** Convert a physical left-to-right lens position into the row's logical tab index. */
internal fun liquidNavigationIndex(position: Float, itemCount: Int, isRtl: Boolean): Int {
    if (itemCount <= 1) return 0
    val physicalIndex = if (position.isFinite()) position.roundToInt().coerceIn(0, itemCount - 1) else 0
    return if (isRtl) itemCount - 1 - physicalIndex else physicalIndex
}

internal fun liquidNavigationVisualIndex(index: Int, itemCount: Int, isRtl: Boolean): Float {
    if (itemCount <= 1) return 0f
    val logicalIndex = index.coerceIn(0, itemCount - 1)
    return (if (isRtl) itemCount - 1 - logicalIndex else logicalIndex).toFloat()
}
