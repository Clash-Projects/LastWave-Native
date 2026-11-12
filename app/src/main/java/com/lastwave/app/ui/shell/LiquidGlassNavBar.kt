package com.lastwave.app.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.theme.LayerBackdrop
import com.lastwave.app.ui.theme.liquidGlass
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

private val BarHeight = 56.dp
private val BarInset = 4.dp
private val CellWidth = 76.dp

/** How much wider (and half as much flatter) the lens gets halfway between two tabs. */
private const val LENS_STRETCH = 0.24f

/**
 * Liquid Glass navigation: a compact floating capsule with one glass lens
 * that slides between tabs and stretches while it travels. The lens tracks
 * the pager while it scrolls, so a swipe drags it and a tap glides it.
 *
 * Element MUST be a sibling of the backdrop source, never inside it.
 */
@Composable
internal fun LiquidGlassNavBar(
    backdrop: LayerBackdrop?,
    tabs: List<MainTab>,
    selectedIndex: Int,
    /** Fractional page while the pager is scrolling, null at rest. */
    pagerPosition: () -> Float?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lens = remember { Animatable(selectedIndex.toFloat()) }
    val selected by rememberUpdatedState(selectedIndex)
    LaunchedEffect(lens) {
        snapshotFlow { pagerPosition() to selected }.collectLatest { (live, target) ->
            if (live != null) lens.snapTo(live) else lens.animateTo(target.toFloat(), ExpressiveMotion.spatialSlow())
        }
    }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(CellWidth * tabs.size + BarInset * 2, BarHeight)
                .navGlass(backdrop, blurScale = 1f, maxScrim = 0.36f)
                .padding(BarInset),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    // Size and position are read in layout, so the lens moves without recomposing the bar.
                    .layout { measurable, constraints ->
                        val cell = constraints.maxWidth / tabs.size.toFloat()
                        val position = lens.value
                        val travel = sin(PI * (position - floor(position))).toFloat()
                        val width = cell * (1f + LENS_STRETCH * travel)
                        val height = constraints.maxHeight * (1f - LENS_STRETCH / 2f * travel)
                        val placeable = measurable.measure(Constraints.fixed(width.roundToInt(), height.roundToInt()))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.placeRelative(
                                (cell * position + (cell - width) / 2f).roundToInt(),
                                ((constraints.maxHeight - height) / 2f).roundToInt(),
                            )
                        }
                    }
                    // A clearer, barely tinted pane over the frosted capsule is what reads as depth.
                    .navGlass(backdrop, blurScale = 0.35f, maxScrim = 0.1f)
                    .background(colors.primary.copy(alpha = 0.16f), CircleShape)
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(colors.onSurface.copy(alpha = 0.4f), colors.onSurface.copy(alpha = 0.04f)),
                        ),
                        CircleShape,
                    ),
            )
            Row(Modifier.matchParentSize().selectableGroup()) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) colors.primary else colors.onSurfaceVariant,
                        animationSpec = ExpressiveMotion.effectsDefault(),
                        label = "glassNavTint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = isSelected,
                                interactionSource = remember { MutableInteractionSource() },
                                // The lens gliding to the tab is the feedback.
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(tab.icon(isSelected), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(
                            text = stringResource(tab.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Capsule glass, or a tinted capsule where the backdrop is unavailable (below API 31). */
@Composable
private fun Modifier.navGlass(backdrop: LayerBackdrop?, blurScale: Float, maxScrim: Float): Modifier =
    if (backdrop != null) {
        liquidGlass(backdrop, 0.5f, CircleShape, interactive = false, blurScale = blurScale, minScrim = 0f, maxScrim = maxScrim)
    } else {
        clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
    }
