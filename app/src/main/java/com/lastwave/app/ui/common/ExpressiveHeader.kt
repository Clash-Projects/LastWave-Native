package com.lastwave.app.ui.common

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lastwave.app.ui.theme.Backdrop
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassSource
import com.lastwave.app.ui.theme.rememberLayerBackdrop

/** Only the bottom corners are rounded (24dp) for smooth full-bleed top alignment. */
private val HeaderShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

/**
 * The unified top container for all major screens.
 * Uses exact pure Kyant liquid glass chrome matching the navigation bar and player bar.
 * Window-inset aware: content pads for the status bar while the glass extends behind it.
 */
@Composable
fun ExpressiveHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    backdrop: Backdrop? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val liquidGlass = LocalLiquidGlass.current
    val isGlass = liquidGlass && isLiquidGlassBackdropSupported()
    val isDark = LocalIsDarkTheme.current
    val backgroundColor = MaterialTheme.colorScheme.background
    val isCustomColor = containerColor != Color.Unspecified
    val isTransparent = isCustomColor && containerColor == Color.Transparent

    // Local sibling backdrop so ExpressiveHeader is completely decoupled from
    // parent captures and never triggers HWUI render recursion.
    val headerBackdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }

    val effectiveBackdrop = backdrop ?: headerBackdrop
    val useGlass = isGlass && !isCustomColor && effectiveBackdrop != null

    val resolvedContainerColor = when {
        isCustomColor -> containerColor
        useGlass -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    Box(modifier.fillMaxWidth().zIndex(1f)) {
        if (useGlass && backdrop == null && headerBackdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(HeaderShape)
                    .liquidGlassSource(headerBackdrop),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    (if (isDark) Color.White else Color.Black).copy(alpha = if (isDark) 0.05f else 0.02f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        }

        Surface(
            shape = if (isTransparent) RectangleShape else HeaderShape,
            color = resolvedContainerColor,
            tonalElevation = if (useGlass || isTransparent) 0.dp else 2.dp,
            shadowElevation = if (useGlass || isTransparent) 0.dp else 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (useGlass && effectiveBackdrop != null) {
                        Modifier.liquidGlassChrome(
                            shape = HeaderShape,
                            enabled = true,
                            preset = LiquidGlassPreset.Header,
                            backdrop = effectiveBackdrop,
                        )
                    } else {
                        Modifier
                    }
                ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
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
                                containerColor = if (useGlass) Color.White.copy(alpha = if (isDark) 0.12f else 0.20f)
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (useGlass) (if (isDark) Color.White else Color.Black)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = if (useGlass) (if (isDark) Color.White else Color.Black)
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (useGlass) (if (isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f))
                                else MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * A trailing action icon for [ExpressiveHeader] styled with glass-compatible translucent tonal styling.
 */
@Composable
fun HeaderActionIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    val isGlass = LocalLiquidGlass.current && isLiquidGlassBackdropSupported()
    val isDark = LocalIsDarkTheme.current
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (isGlass) Color.White.copy(alpha = if (isDark) 0.12f else 0.20f)
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (isGlass) (if (isDark) Color.White else Color.Black)
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}
