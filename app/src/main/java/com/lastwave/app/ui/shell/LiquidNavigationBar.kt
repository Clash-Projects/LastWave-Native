package com.lastwave.app.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.liquid
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class LiquidNavigationItem(val label: String, val icon: ImageVector)
internal enum class LiquidNavigationStyle { Tabs, Material3 }
private val LensShape = CircleShape

@Composable
internal fun LiquidNavigationBar(
    liquidState: LiquidState,
    items: List<LiquidNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onScrubSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: LiquidNavigationStyle = LiquidNavigationStyle.Tabs,
) {
    key(style) {
        LiquidNavigationBarContent(liquidState, items, selectedIndex, onSelect, onScrubSelect, modifier, style)
    }
}

@Composable
private fun LiquidNavigationBarContent(
    pageState: LiquidState,
    items: List<LiquidNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onScrubSelect: (Int) -> Unit,
    modifier: Modifier,
    style: LiquidNavigationStyle,
) {
    if (items.isEmpty()) return
    val compact = style == LiquidNavigationStyle.Material3
    val isDark = LocalIsDarkTheme.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val count = items.size
    val selected by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState(onSelect)
    val scrub by rememberUpdatedState(onScrubSelect)
    val start = liquidNavigationVisualIndex(selectedIndex, count, isRtl)
    val settledPosition = remember(count, isRtl) { Animatable(start) }
    val trailingPosition = remember(count, isRtl) { Animatable(start) }
    var pressed by remember { mutableStateOf(false) }
    var touchPosition by remember { mutableFloatStateOf(Float.NaN) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var captionAtPress by remember { mutableIntStateOf(selectedIndex) }
    var releaseGeneration by remember { mutableIntStateOf(0) }
    val lift by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = 0.90f, stiffness = 520f),
        label = "liquidDropLift",
    )
    val currentPosition = {
        if (touchPosition.isFinite()) touchPosition else settledPosition.value
    }
    LaunchedEffect(selectedIndex, pressed, releaseGeneration) {
        if (!pressed) {
            if (touchPosition.isFinite()) {
                settledPosition.snapTo(touchPosition)
                touchPosition = Float.NaN
            }
            settledPosition.animateTo(
                liquidNavigationVisualIndex(selectedIndex, count, isRtl),
                spring(dampingRatio = 0.95f, stiffness = 520f),
            )
        }
    }
    // Only the shape trails the finger. Position has no tab springs during touch.
    LaunchedEffect(count, isRtl) {
        snapshotFlow { currentPosition() }.collectLatest {
            trailingPosition.animateTo(it, spring(dampingRatio = 0.92f, stiffness = 540f))
        }
    }

    val ink = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val neutralInk = ink.copy(alpha = 0.92f)
    val pageColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val railTint = if (isDark) Color(0xFF111319).copy(alpha = 0.32f) else Color.White.copy(alpha = 0.30f)
    val railState = rememberLiquidState()
    val railHeight = if (compact) 60.dp else 64.dp
    val pressedScale = railHeight.value / (railHeight.value - 8f)
    val captionIndex = if (pressed) captionAtPress else selectedIndex
    val railMaxWidth = if (compact) {
        val caption = rememberTextMeasurer().measure(
            text = items[captionIndex.coerceIn(items.indices)].label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            softWrap = false,
            maxLines = 1,
        )
        val captionSlotWidth = with(density) {
            // Match glyph padding and reserve a pixel for equal-slot rounding.
            (caption.size.width + 24.dp.roundToPx() + 6.dp.roundToPx() + 2 * 4.dp.roundToPx() + 1).toDp()
        }
        maxOf(280.dp, captionSlotWidth * count + 8.dp)
    } else 360.dp

    Box(
        modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier.widthIn(max = railMaxWidth)
                .fillMaxWidth().height(railHeight)
                .testTag("liquid_navigation_bar")
                .shadow(8.dp, CircleShape, clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.14f)),
        ) {
            val tabWidth = (maxWidth - 8.dp) / count
            val lensWidth = tabWidth
            val tabPx = with(density) { tabWidth.toPx() }
            val lensPx = with(density) { lensWidth.toPx() }

            // A -> B -> C is acyclic: page A, clear navigation plane B,
            // sibling lens C. The visible opaque rail covers B's original draw.
            // B has its own lower frost, so C is clearer than the outer rail.
            Box(
                Modifier.matchParentSize().clearAndSetSemantics { }
                    .clip(CircleShape)
                    .captureHeadroom(20.dp)
                    .liquefiable(railState)
                    .background(pageColor)
                    .liquid(pageState) {
                        shape = RoundedCornerShape(0.dp)
                        frost = 0.dp
                        refraction = 0f
                        curve = 0f
                        edge = 0f
                    }
                    .padding(20.dp),
            ) {
                Box(Modifier.matchParentSize().background(surfaceColor, CircleShape)
                    .navigationRail(pageState, railTint, blurRadius = 1.5.dp))
                NavigationGlyphs(
                    items, selectedIndex, captionIndex, compact, ink, ink,
                    captured = true, onSelect = {}, modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            }
            // A has an opaque theme background. This covers the clearer source copy
            // completely, including the antialiased edges of text and icons.
            Box(Modifier.matchParentSize().background(surfaceColor, CircleShape).navigationRail(pageState, railTint))
            Box(
                Modifier.fillMaxSize().padding(4.dp).selectableGroup()
                    .pointerInput(count, isRtl, lensPx, tabPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val width = size.width
                            val height = size.height
                            val fingerStart = liquidNavigationRawPosition(down.position.x, width.toFloat(), count)
                            val visualStart = currentPosition()
                            val onExistingLens = abs(fingerStart - visualStart) <= lensPx / (2f * tabPx)
                            val grabOffset = if (onExistingLens) visualStart - fingerStart else 0f
                            captionAtPress = selected
                            touchPosition = liquidNavigationRubberBand(fingerStart + grabOffset, count)
                            touchY = 0f
                            pressed = true
                            var dragged = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (event.changes.count { it.pressed } > 1 ||
                                        size.width != width || size.height != height || change.isConsumed ||
                                        change.position.y < -24.dp.toPx() || change.position.y > height + 24.dp.toPx()
                                    ) {
                                        event.changes.forEach { it.consume() }
                                        break
                                    }
                                    if (!dragged && abs(dy) > viewConfiguration.touchSlop && abs(dy) >= abs(dx)) {
                                        change.consume()
                                        break
                                    }
                                    touchPosition = liquidNavigationRubberBand(
                                        liquidNavigationRawPosition(change.position.x, width.toFloat(), count) + grabOffset,
                                        count,
                                    )
                                    touchY = (dy / height.coerceAtLeast(1)).coerceIn(-0.2f, 0.2f)
                                    if (!change.pressed) {
                                        if (dragged) {
                                            change.consume()
                                            val destination = liquidNavigationIndex(touchPosition, count, isRtl)
                                            if (destination != selected) scrub(destination)
                                        }
                                        break
                                    }
                                    if (abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) dragged = true
                                    // Slop governs event ownership, never visual movement.
                                    if (dragged) change.consume()
                                }
                            } finally {
                                pressed = false
                                touchY = 0f
                                releaseGeneration++
                            }
                        }
                    },
            ) {
                NavigationGlyphs(
                    items, selectedIndex, captionIndex, compact,
                    neutralInk, if (pressed) neutralInk else ink,
                    captured = false, onSelect = { select(it) }, modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
                        IntOffset((currentPosition() * tabPx + (tabPx - lensPx) / 2f).roundToInt(), 0)
                    }.width(lensWidth).fillMaxHeight().graphicsLayer {
                        val amount = lift.coerceIn(0f, 1f)
                        val flow = ((currentPosition() - trailingPosition.value) * 2f).coerceIn(-1f, 1f)
                        // Keep the clearer selection window visible at rest,
                        // as in the first build; never blend doubled glyphs.
                        // Uniform scaling preserves semicircular ends. At rest
                        // the lens fits inside the rail; on press their radii match.
                        scaleX = 1f + amount * (pressedScale - 1f + 0.012f * abs(flow))
                        scaleY = scaleX
                        translationY = (-1.dp.toPx() + touchY * 4.dp.toPx()) * amount
                        shape = LensShape
                        clip = false
                        shadowElevation = (1.5.dp.toPx() + 3.5.dp.toPx() * amount)
                        ambientShadowColor = Color.Black.copy(alpha = 0.08f)
                        spotShadowColor = Color.Black.copy(alpha = 0.16f)
                    }.testTag("liquid_navigation_lens")
                        .liquidBubbleHighlight(isDark) { 0.25f + 0.75f * lift.coerceIn(0f, 1f) }
                        .liquid(railState) {
                            val amount = lift.coerceIn(0f, 1f)
                            shape = LensShape
                            frost = 0.dp
                            refraction = 0.08f + 0.37f * amount
                            curve = 0.04f + 0.16f * amount
                            edge = 0.012f + 0.023f * amount
                            // Chromatic separation belongs to manipulation, not rest.
                            dispersion = 0.14f * amount
                            saturation = 1.10f
                            tint = Color.White.copy(alpha = if (isDark) 0.012f else 0.018f)
                        },
                )
            }
        }
    }
}

/** Sample headroom without changing hit areas or painting over adjacent overlays. */
private fun Modifier.captureHeadroom(padding: Dp): Modifier = layout { measurable, constraints ->
    val inset = padding.roundToPx()
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(width + inset * 2, height + inset * 2))
    layout(width, height) { placeable.place(-inset, -inset) }
}

private fun Modifier.navigationRail(state: LiquidState, color: Color, blurRadius: Dp = 8.dp): Modifier = liquid(state) {
    shape = CircleShape
    frost = blurRadius
    refraction = 0.07f
    curve = 0.12f
    edge = 0.012f
    saturation = 1.1f
    dispersion = 0.01f
    tint = color
}
