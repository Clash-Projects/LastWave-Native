package com.lastwave.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.headerLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassSource
import com.lastwave.app.ui.theme.rememberLayerBackdrop

/** Only the bottom corners are rounded (24dp) for smooth full-bleed top alignment. */
private val HeaderShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

/**
 * Theme-tinted wash for the glass to blur when no content sits under the
 * header. Over the flat page colour the glass had nothing to refract and
 * read as a black bar.
 */
@Composable
internal fun headerGlassWash(): Brush {
    val scheme = MaterialTheme.colorScheme
    return Brush.horizontalGradient(
        listOf(
            scheme.primary.copy(alpha = 0.20f),
            scheme.tertiary.copy(alpha = 0.08f),
            scheme.secondary.copy(alpha = 0.16f),
        ),
    )
}

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
    /** Extra row drawn inside the same glass pane, under the title row
     *  (e.g. Home's chip strip), full width with no side padding. */
    bottomContent: (@Composable ColumnScope.() -> Unit)? = null,
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
        else -> MaterialTheme.colorScheme.surface
    }
    val headerShape = if (useGlass && !isTransparent) HeaderShape else RectangleShape

    Box(modifier.fillMaxWidth().zIndex(1f)) {
        if (useGlass && backdrop == null && headerBackdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(HeaderShape)
                    .liquidGlassSource(headerBackdrop),
            ) {
                Box(Modifier.fillMaxSize().background(headerGlassWash()))
            }
        }

        Surface(
            shape = headerShape,
            color = resolvedContainerColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (useGlass && effectiveBackdrop != null) {
                        Modifier.headerLiquidGlass(
                            backdrop = effectiveBackdrop,
                            shape = HeaderShape,
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
                    .padding(bottom = if (bottomContent != null) 4.dp else 8.dp),
            ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (onBack != null && !useGlass) 8.dp else 20.dp,
                        end = if (useGlass) 20.dp else 12.dp,
                    )
                    .padding(top = 4.dp, bottom = if (bottomContent != null) 4.dp else 0.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        if (useGlass) {
                            FilledTonalIconButton(
                                onClick = onBack,
                                shapes = IconButtonDefaults.shapes(),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.White.copy(alpha = if (isDark) 0.12f else 0.20f),
                                    contentColor = if (isDark) Color.White else Color.Black,
                                ),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(Modifier.width(12.dp))
                        } else {
                            IconButton(
                                onClick = onBack,
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }

                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
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

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        actions()
                    }
                }
            }
            bottomContent?.invoke(this)
            }
        }
    }
}

/**
 * A trailing action icon for [ExpressiveHeader]. Uses borderless Material 3 IconButton styling
 * in standard mode (matching TopAppBar), or translucent tonal styling in Liquid Glass mode.
 */
@Composable
fun HeaderActionIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    val isGlass = LocalLiquidGlass.current && isLiquidGlassBackdropSupported()
    val isDark = LocalIsDarkTheme.current
    if (isGlass) {
        FilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.White.copy(alpha = if (isDark) 0.12f else 0.20f),
                contentColor = if (isDark) Color.White else Color.Black,
            ),
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
    } else {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}
