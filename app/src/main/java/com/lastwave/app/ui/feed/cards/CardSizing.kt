/*
 * Responsive card sizing for native shelf cards. Widths derive from the
 * available viewport so ~2.3 cards peek on phones and scale up on tablets /
 * landscape. Pure Dp math — no recomposition cost beyond configuration.
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Square card width: ~2.3 cards visible on phones, larger on wide screens. */
@Composable
fun rememberSquareCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return when {
        screenWidth < 360.dp -> (screenWidth * 0.40f).coerceAtLeast(120.dp)
        screenWidth < 600.dp -> (screenWidth * 0.40f).coerceAtMost(180.dp)
        screenWidth < 840.dp -> (screenWidth * 0.24f).coerceAtMost(200.dp)
        else -> (screenWidth * 0.16f).coerceAtMost(216.dp)
    }
}

/** Wide 16:9 card width: ~1.6 cards visible on phones, larger on tablets. */
@Composable
fun rememberWideCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return when {
        screenWidth < 360.dp -> (screenWidth * 0.62f).coerceAtLeast(200.dp)
        screenWidth < 600.dp -> (screenWidth * 0.58f).coerceAtMost(300.dp)
        screenWidth < 840.dp -> (screenWidth * 0.38f).coerceAtMost(340.dp)
        else -> (screenWidth * 0.26f).coerceAtMost(360.dp)
    }
}

/** Featured big-square card width: ~1.7 cards peek on phones. */
@Composable
fun rememberFeaturedCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return when {
        screenWidth < 360.dp -> (screenWidth * 0.56f).coerceAtLeast(180.dp)
        screenWidth < 600.dp -> (screenWidth * 0.54f).coerceAtMost(260.dp)
        screenWidth < 840.dp -> (screenWidth * 0.36f).coerceAtMost(300.dp)
        else -> (screenWidth * 0.24f).coerceAtMost(320.dp)
    }
}

/** Community playlist card width: a wide glass card, ~1.3 peek on phones. */
@Composable
fun rememberCommunityCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return when {
        screenWidth < 360.dp -> (screenWidth * 0.78f).coerceAtLeast(260.dp)
        screenWidth < 600.dp -> (screenWidth * 0.76f).coerceAtMost(340.dp)
        screenWidth < 840.dp -> (screenWidth * 0.5f).coerceAtMost(380.dp)
        else -> (screenWidth * 0.34f).coerceAtMost(400.dp)
    }
}
