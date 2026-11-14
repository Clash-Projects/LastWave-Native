package com.lastwave.app.ui.settings


import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp



data class ToggleDetailInfo(
    val title: String,
    val subtitle: String,
    val checked: Boolean,
)

val LocalToggleDetailViewer = staticCompositionLocalOf<((ToggleDetailInfo) -> Unit)?> { null }

// -- Expressive shape scale used only within this screen --
internal val CardOuterShape = RoundedCornerShape(28.dp)
internal val IconBadgeShape = RoundedCornerShape(14.dp)

/** Where a row sits within a visually-connected group of settings rows —
 *  drives per-row corner radii so a multi-row group reads as one premium
 *  surface split into rows, not a stack of separate cards (see groupShape
 *  below and the GROUP_GAP spacing used between rows in a group's Column). */
internal enum class GroupPosition { SINGLE, TOP, MIDDLE, BOTTOM }

internal val GROUP_OUTER_RADIUS = 28.dp
internal val GROUP_INNER_RADIUS = 6.dp
internal val GROUP_GAP = 3.dp

internal fun groupShape(position: GroupPosition): RoundedCornerShape = when (position) {
    GroupPosition.SINGLE -> RoundedCornerShape(GROUP_OUTER_RADIUS)
    GroupPosition.TOP -> RoundedCornerShape(
        topStart = GROUP_OUTER_RADIUS, topEnd = GROUP_OUTER_RADIUS,
        bottomStart = GROUP_INNER_RADIUS, bottomEnd = GROUP_INNER_RADIUS,
    )
    GroupPosition.MIDDLE -> RoundedCornerShape(GROUP_INNER_RADIUS)
    GroupPosition.BOTTOM -> RoundedCornerShape(
        topStart = GROUP_INNER_RADIUS, topEnd = GROUP_INNER_RADIUS,
        bottomStart = GROUP_OUTER_RADIUS, bottomEnd = GROUP_OUTER_RADIUS,
    )
}

/** Wraps a fixed list of settings rows and assigns each one its
 *  GroupPosition automatically — SINGLE for a lone row, TOP/BOTTOM for the
 *  ends of a longer group, MIDDLE for everything between. Rows are stacked
 *  with a tiny GROUP_GAP rather than normal item spacing, so the group
 *  reads as one connected surface with rows peeking through a hairline gap
 *  rather than a list of separate cards. */
@Composable
internal fun SettingsGroup(rowCount: Int, content: @Composable (index: Int, position: GroupPosition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        for (i in 0 until rowCount) {
            val position = when {
                rowCount == 1 -> GroupPosition.SINGLE
                i == 0 -> GroupPosition.TOP
                i == rowCount - 1 -> GroupPosition.BOTTOM
                else -> GroupPosition.MIDDLE
            }
            content(i, position)
        }
    }
}

/** [SettingsGroup] for sections whose rows appear conditionally. Building the
 *  list first keeps the corner radii correct without a hand-counted rowCount
 *  that silently drifts when a row is added. */
@Composable
internal fun SettingsRows(rows: List<@Composable (GroupPosition) -> Unit>) {
    SettingsGroup(rowCount = rows.size) { index, position -> rows[index](position) }
}


/** Small tap-scale used across the row-style cards on this screen for a
 *  softer, springier press response than the plain ripple alone gives. */
@Composable
internal fun rememberPressScale(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return scale
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun IconBadge(icon: ImageVector, container: Color, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(IconBadgeShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun IconBadge(painter: Painter, container: Color, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(IconBadgeShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun SettingsToggleCard(
    painter: Painter,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
    enabled: Boolean = true,
    isHighlighted: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)
    val shape = groupShape(position)
    val haptic = LocalHapticFeedback.current
    val detailViewer = LocalToggleDetailViewer.current
    @Suppress("NAME_SHADOWING")
    val onCheckedChange: (Boolean) -> Unit = { value ->
        haptic.performHapticFeedback(if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        onCheckedChange(value)
    }

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (enabled) scale else 1f
                scaleX = s
                scaleY = s
            }
            .settingHighlightGlow(isHighlighted = isHighlighted, shape = shape)
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    detailViewer?.invoke(ToggleDetailInfo(title, subtitle, checked))
                },
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                painter,
                if (enabled) iconContainer else iconContainer.copy(alpha = 0.5f),
                if (enabled) iconTint else iconTint.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = if (enabled) onCheckedChange else null,
                thumbContent = if (checked) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null,
            )
        }
    }
}

@Composable
internal fun SettingsToggleCard(
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
    enabled: Boolean = true,
    isHighlighted: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)
    val shape = groupShape(position)
    val haptic = LocalHapticFeedback.current
    val detailViewer = LocalToggleDetailViewer.current
    @Suppress("NAME_SHADOWING")
    val onCheckedChange: (Boolean) -> Unit = { value ->
        haptic.performHapticFeedback(if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        onCheckedChange(value)
    }

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (enabled) scale else 1f
                scaleX = s
                scaleY = s
            }
            .settingHighlightGlow(isHighlighted = isHighlighted, shape = shape)
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    detailViewer?.invoke(ToggleDetailInfo(title, subtitle, checked))
                },
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon,
                if (enabled) iconContainer else iconContainer.copy(alpha = 0.5f),
                if (enabled) iconTint else iconTint.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = if (enabled) onCheckedChange else null,
                thumbContent = if (checked) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null,
            )
        }
    }
}

@Composable
internal fun SettingsActionCard(
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
    isHighlighted: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)
    val titleColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val shape = groupShape(position)

    Card(
        onClick = onClick,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .settingHighlightGlow(isHighlighted = isHighlighted, shape = shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, iconContainer, iconTint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = titleColor, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
