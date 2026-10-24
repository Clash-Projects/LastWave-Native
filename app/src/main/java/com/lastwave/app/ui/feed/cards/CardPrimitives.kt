/*
 * Shared primitives for the native shelf card system (liquid-glass style).
 * Press scale, haptics, crossfade artwork with soft placeholder, info line.
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.ui.common.ArtworkImage

/** Modifier that adds the shared press-scale + haptic + click/long-click. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.cardPressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    contentDescription: String? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "cardPressScale",
    )
    return this
        .scale(scale)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            onLongClick = onLongClick?.let {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    it()
                }
            },
        )
}

/** Rounded-corner artwork surface with the app's crossfade loader + fallback. */
@Composable
internal fun CardArtwork(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    decodeSizePx: Int? = null,
) {
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius))) {
            ArtworkImage(
                name = title,
                artist = subtitle,
                embeddedUrl = artworkUrl,
                fallbackIcon = fallbackIcon,
                decodeSizePx = decodeSizePx,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Bold card title, ellipsized. */
@Composable
internal fun CardTitle(text: String, maxLines: Int = 1, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** One-line muted info line (artist, type, plays/views). */
@Composable
internal fun CardInfoLine(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = modifier,
    )
}
