package com.lastwave.app.ui.tv.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standard TV overscan margins (5% safe area = 48dp horizontal, 27dp vertical at 1080p).
 * Prevents UI elements from being clipped by television bezels and overscan calibrations.
 */
val TvHorizontalOverscan: Dp = 48.dp
val TvVerticalOverscan: Dp = 27.dp

@Composable
fun TvSafeArea(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = TvHorizontalOverscan,
    verticalPadding: Dp = TvVerticalOverscan,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        content = content,
    )
}
