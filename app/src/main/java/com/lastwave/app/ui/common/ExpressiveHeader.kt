package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import com.lastwave.app.ui.theme.LocalLiquidGlassBackdrop
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor

/** Only the bottom corners are rounded (24dp) for smooth full-bleed top alignment. */
private val HeaderShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

/**
 * The unified top container for all major screens.
 * Uses exact pure Kyant liquid glass chrome matching the navigation bar and player bar.
 */
@Composable
fun ExpressiveHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val liquidGlass = LocalLiquidGlass.current
    val isGlass = liquidGlass && isLiquidGlassBackdropSupported()
    val isDark = LocalIsDarkTheme.current
    val isCustomColor = containerColor != Color.Unspecified
    val isTransparent = isCustomColor && containerColor == Color.Transparent
    val useGlass = isGlass && !isCustomColor && backdrop != null

    val resolvedContainerColor = when {
        isCustomColor -> containerColor
        useGlass -> liquidGlassContainerColor(
            color = MaterialTheme.colorScheme.surfaceContainer,
            enabled = true,
            backdrop = backdrop,
        )
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    Box(modifier.fillMaxWidth().zIndex(1f)) {
        Surface(
            shape = if (isTransparent) RectangleShape else HeaderShape,
            color = resolvedContainerColor,
            tonalElevation = if (useGlass || isTransparent) 0.dp else 2.dp,
            shadowElevation = if (useGlass || isTransparent) 0.dp else 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (useGlass && backdrop != null) {
                        Modifier.liquidGlassChrome(
                            shape = HeaderShape,
                            enabled = true,
                            preset = LiquidGlassPreset.Header,
                            backdrop = backdrop,
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

/** A trailing action icon for [ExpressiveHeader] styled with glass-compatible translucent tonal styling. */
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
        ),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}
