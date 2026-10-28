package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.highlight.Highlight
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.liquidGlass
import com.lastwave.app.ui.theme.liquidGlassSource
import com.lastwave.app.ui.theme.rememberLayerBackdrop


/** Only the bottom corners are rounded, and a modest 24dp at that (not
 *  36dp) — a short header (just a title row, no back button, minimal
 *  padding) can be barely taller than 2x a large radius, and when the two
 *  bottom-corner arcs get close enough to touch they don't blend, they
 *  clip into a visible seam right where they meet. 24dp stays safely
 *  smaller than the shortest real header's content height on every screen
 *  that uses this. */
private val HeaderShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

/**
 * The one container every major screen's header sits inside. Full-bleed
 * width with no side margins and no gap above it — it IS the top of the
 * screen, edge to edge, with only its bottom edge rounded.
 *
 * The depth behind it is a pair of broad radial gradients (accent colors
 * fading to fully transparent) painted directly as a background, not a
 * blurred solid shape — Modifier.blur() clamps/repeats pixels at its own
 * layer bounds, which reads as a hard smear or a visible line right at the
 * glow's edge instead of a smooth fade to nothing. A gradient that's
 * already transparent at its own edge has no edge to see.
 *
 * Window-inset aware: its CONTENT pads for the status bar, while the
 * colored surface itself extends up behind the status bar — callers never
 * add their own top safe-drawing padding above this.
 *
 * For a screen with only a title (a tab root, no back target) pass
 * `onBack = null`; for a pushed screen pass a pop callback and it renders a
 * leading back button in the same tonal family as the trailing [actions].
 */
@Composable
fun ExpressiveHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val liquidGlass = LocalLiquidGlass.current
    val isGlass = liquidGlass && isLiquidGlassBackdropSupported()
    val glow = MaterialTheme.colorScheme.primary
    val secondaryGlow = MaterialTheme.colorScheme.tertiary
    val backgroundColor = MaterialTheme.colorScheme.background
    val isCustomColor = containerColor != Color.Unspecified
    val isTransparent = isCustomColor && containerColor == Color.Transparent
    val useGlass = isGlass && !isCustomColor

    // Backdrop for liquid glass header board — unconditional remember keeps composition stable.
    val headerBackdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }

    val resolvedContainerColor = when {
        isCustomColor -> containerColor
        useGlass -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    Box(modifier.fillMaxWidth().zIndex(1f)) {
        if (useGlass && headerBackdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(HeaderShape)
                    .liquidGlassSource(headerBackdrop),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawGlowBackground(glow, secondaryGlow),
                )
            }
        }

        Surface(
            shape = if (isTransparent) androidx.compose.ui.graphics.RectangleShape else HeaderShape,
            color = resolvedContainerColor,
            tonalElevation = if (useGlass || isTransparent) 0.dp else 2.dp,
            shadowElevation = if (useGlass || isTransparent) 0.dp else 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (useGlass && headerBackdrop != null) {
                        Modifier.liquidGlass(
                            backdrop = headerBackdrop,
                            shape = HeaderShape,
                            interactive = false,
                            highlight = Highlight.Default,
                        )
                    } else {
                        Modifier
                    }
                ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (!useGlass && !isTransparent) {
                            Modifier.drawGlowBackground(glow, secondaryGlow)
                        } else {
                            Modifier
                        }
                    )
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = 20.dp)
                    .padding(top = 2.dp, bottom = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        FilledTonalIconButton(
                            onClick = onBack,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (liquidGlass) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.width(12.dp))
                    }

                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = if (onBack != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        actions()
                    }
                }
            }
        }
    }
}

/** Cached accent glows for [ExpressiveHeader]. Subtly tuned so it harmonizes
 * with dark and signature schemes without creating jarring color washes. */
@Composable
private fun Modifier.drawGlowBackground(color: Color, secondaryColor: Color): Modifier {
    return this.drawWithCache {
        val cx = size.width * 0.58f
        val cy = size.height * 0.46f
        val radius = (size.width.coerceAtLeast(size.height) * 1.08f).coerceAtLeast(1f)
        val secondaryCx = size.width * 0.38f
        val secondaryRadius = (size.width.coerceAtLeast(size.height) * 0.72f).coerceAtLeast(1f)
        val secondaryBrush = Brush.radialGradient(
            colors = listOf(
                secondaryColor.copy(alpha = 0.05f),
                secondaryColor.copy(alpha = 0.015f),
                secondaryColor.copy(alpha = 0f),
            ),
            center = Offset(secondaryCx, size.height * 0.78f),
            radius = secondaryRadius,
        )
        val primaryBrush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.08f),
                color.copy(alpha = 0.025f),
                color.copy(alpha = 0f),
            ),
            center = Offset(cx, cy),
            radius = radius,
        )
        onDrawBehind {
            drawRect(brush = secondaryBrush)
            drawRect(brush = primaryBrush)
        }
    }
}

/** A trailing action icon for [ExpressiveHeader] (or any other header-style
 *  surface) — a small filled-tonal circular button matching the leading
 *  back button's tone, so every icon in a header reads as one family
 *  instead of each screen picking its own IconButton style. */
@Composable
fun HeaderActionIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}
