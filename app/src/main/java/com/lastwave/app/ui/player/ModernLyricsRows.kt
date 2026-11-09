package com.lastwave.app.ui.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Share
import com.lastwave.app.ui.player.lyricsshare.ShareLyricsDialog
import com.lastwave.app.ui.player.lyricsshare.TrackInfo
import com.lastwave.app.ui.player.lyricsshare.buildLyricsSnapshot
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.Slider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import com.lastwave.app.R
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.local.LyricsProvider
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable
import com.lastwave.app.data.lyrics.LyricsTranslation
import com.lastwave.app.data.lyrics.VocalRole
import com.lastwave.app.data.lyrics.isRtlText
import com.lastwave.app.data.lyrics.providerForSource
import com.lastwave.app.data.lyrics.translationLanguageName
import com.lastwave.app.data.lyrics.translationWireTarget
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale

/**
 * Synced lyrics list, word-by-word and line-by-line alike.
 *
 * Sync contract:
 * - Every item starts at a provider timestamp ([buildLyricsStage] never
 *   re-times rows). The active item is the last one whose start is <= the
 *   effective position (binary search), so two items can never both hold
 *   focus because of a boundary.
 * - Word fill reads each unit's own `[timeMs, timeMs + durationMs)` window
 *   from the vsync clock in [SyncedLyricsProgressHost]; position is the
 *   interpolated playhead plus the manual offset, never quantized.
 *
 * Frame cost contract:
 * - Playback progress is read only inside draw / graphicsLayer / layout
 *   lambdas and `derivedStateOf` gates, never in composition. A row
 *   recomposes only when it flips between lit and unlit.
 * - Focus falloff, scale and the elastic trail come from two list-wide
 *   animations ([focus], [trail]) evaluated per row in `graphicsLayer`
 *   instead of five animators per row.
 * - No per-row blur. The karaoke wipe's offscreen layer is bounded to the
 *   visual row being sung, not the whole text block.
 */
@Composable
internal fun ModernSyncedLyricsList(
    stage: LyricsStage,
    sourceLines: List<LyricLine>,
    isOverallRtl: Boolean,
    normalStyle: TextStyle,
    accompanimentStyle: TextStyle,
    currentPositionMs: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Aligned sub-line per source row index ([LyricsTranslation] output), or null. */
    subLines: List<String?>? = null,
    /** Shared 0..1 reveal for every sub-line; read per frame, not recomposed. */
    subReveal: () -> Float = { 0f },
    subLineStyle: TextStyle? = null,
    displayMode: LyricsDisplayMode = LyricsDisplayMode.Original,
) {
    val items = stage.items
    val listState = rememberLazyListState()
    var userScrolledTime by remember { mutableLongStateOf(0L) }
    val currentPositionMsState by rememberUpdatedState(currentPositionMs)

    val activeState = remember(stage) {
        derivedStateOf { stage.activeIndex(currentPositionMsState()) }
    }
    val activeIndex = activeState.value

    val isDraggedState = listState.interactionSource.collectIsDraggedAsState()
    // [0] = a user drag or the fling it started is still moving the list.
    // Plain array, read only from effects, so it never recomposes. The old
    // code stamped the time only while composing at drag *start*: a drag
    // longer than the grace period, or the fling after it, was yanked back
    // to the sung line mid-motion by the next line change (scroll jitter).
    val userScrollActive = remember { BooleanArray(1) }
    LaunchedEffect(listState) {
        snapshotFlow { isDraggedState.value to listState.isScrollInProgress }
            .collect { (dragged, scrolling) ->
                if (dragged) userScrollActive[0] = true
                if (userScrollActive[0]) userScrolledTime = System.currentTimeMillis()
                if (!dragged && !scrolling) userScrollActive[0] = false
            }
    }
    val isPlayingState by rememberUpdatedState(isPlaying)

    // One focus value for the whole list: rows derive alpha and scale from
    // their distance to it, so a line change animates every row with a
    // single animation instead of three animators per row.
    val focus = remember(stage) { Animatable(activeState.value.toFloat()) }
    // Elastic trail clock: 0 -> 1 after a forward line change; rows below
    // the new line read their own staggered slice of it.
    val trail = remember(stage) { Animatable(1f) }
    val lastActive = remember(stage) { intArrayOf(activeState.value) }
    LaunchedEffect(stage, activeIndex) {
        val from = lastActive[0]
        lastActive[0] = activeIndex
        val target = activeIndex.toFloat()
        launch {
            if (kotlin.math.abs(target - focus.value) > 3f) {
                focus.snapTo(target)
            } else {
                focus.animateTo(target, tween(LYRIC_FOCUS_MS, easing = LYRIC_EASE))
            }
        }
        if (activeIndex == from + 1 && activeIndex > 0 && isPlayingState && !isDraggedState.value) {
            trail.snapTo(0f)
            trail.animateTo(1f, tween(LYRIC_TRAIL_TOTAL_MS, easing = LinearEasing))
        } else if (trail.value != 1f) {
            trail.snapTo(1f)
        }
    }

    LaunchedEffect(activeIndex, isPlaying) {
        // Auto-follow yields to the user for a short grace period after a
        // manual scroll; a paused player has no urgency, so the grace is
        // longer and the line only takes the focus back once the user is
        // clearly done browsing.
        val resumeDelayMs = if (isPlaying) 600L else 5000L
        val timeSinceUserScroll = System.currentTimeMillis() - userScrolledTime
        if (!userScrollActive[0] && timeSinceUserScroll > resumeDelayMs && activeIndex in items.indices) {
            runCatching {
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                if (viewportHeight > 0) {
                    // Upper-third anchor: the sung line sits 18% from the top.
                    val targetY = (viewportHeight * LYRIC_ANCHOR_FRACTION).toInt()
                    val targetItem = layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                    if (targetItem != null) {
                        val delta = targetItem.offset - targetY
                        listState.animateScrollBy(delta.toFloat(), lyricScrollDurationMs(delta.toFloat()))
                    } else {
                        listState.animateScrollToItem(index = activeIndex, scrollOffset = -targetY)
                    }
                }
            }
        }
    }

    val focusValue: () -> Float = remember(focus) { { focus.value } }
    val trailValue: () -> Float = remember(trail) { { trail.value } }

    LazyColumn(
        state = listState,
        modifier = modifier.lyricsFadingEdge(topFadeDp = 36.dp, bottomFadeDp = 110.dp),
        contentPadding = PaddingValues(top = 40.dp, bottom = 140.dp, start = 20.dp, end = 20.dp),
    ) {
        itemsIndexed(
            items,
            key = { index, item -> "${if (item is LyricInterlude) "b" else "g"}$index:${item.startMs}" },
            contentType = { _, item ->
                when (item) {
                    is LyricInterlude -> "interlude"
                    is LyricLineGroup -> if (item.isWordSynced) "word" else "line"
                }
            },
        ) { index, item ->
            when (item) {
                is LyricInterlude -> LyricInterludeRow(
                    item = item,
                    index = index,
                    activeState = activeState,
                    isOverallRtl = isOverallRtl,
                    currentPositionMs = currentPositionMs,
                )
                is LyricLineGroup -> {
                    val subLine = subLines?.getOrNull(item.subLineIndex)
                        ?: if (displayMode == LyricsDisplayMode.Romanized) {
                            sourceLines.getOrNull(item.subLineIndex)?.transliteration
                        } else null
                    LyricGroupRow(
                        group = item,
                        index = index,
                        isStaged = stage.isStaged,
                        isOverallRtl = isOverallRtl,
                        activeState = activeState,
                        focus = focusValue,
                        trail = trailValue,
                        browsing = isDraggedState,
                        currentPositionMs = currentPositionMs,
                        normalStyle = normalStyle,
                        accompanimentStyle = accompanimentStyle,
                        subLine = subLine,
                        subLineStyle = subLineStyle,
                        subReveal = subReveal,
                        onSeek = onSeek,
                    )
                }
            }
        }
    }
}


/** Alpha for a row [dist] items away from the focus, interpolated. */
internal fun lyricFalloffAlpha(dist: Float): Float {
    val last = LINE_FALLOFF_ALPHA.lastIndex
    val d = dist.coerceIn(0f, last.toFloat())
    val i = d.toInt()
    val frac = d - i
    val a = LINE_FALLOFF_ALPHA[i]
    val b = LINE_FALLOFF_ALPHA[(i + 1).coerceAtMost(last)]
    return a + (b - a) * frac
}

/**
 * Elastic trail for the row [rel] items below the new active row: it starts
 * a little low and springs into place with a slight overshoot, staggered per
 * row, so the lines below follow the scroll like a sheet rather than a block.
 */

internal fun lyricTrailOffsetDp(rel: Int, clock: Float): Float {
    if (rel !in 0..6 || clock >= 1f) return 0f
    val elapsed = clock * LYRIC_TRAIL_TOTAL_MS - rel * LYRIC_TRAIL_STAGGER_MS
    val t = (elapsed / LYRIC_TRAIL_SETTLE_MS).coerceIn(0f, 1f)
    val c1 = 1.4f
    val c3 = c1 + 1f
    val u = t - 1f
    val eased = 1f + c3 * u * u * u + c1 * u * u
    return (7f + rel * 3f) * (1f - eased)
}


@Composable
internal fun LyricGroupRow(
    group: LyricLineGroup,
    index: Int,
    isStaged: Boolean,
    isOverallRtl: Boolean,
    activeState: androidx.compose.runtime.State<Int>,
    focus: () -> Float,
    trail: () -> Float,
    browsing: androidx.compose.runtime.State<Boolean>,
    currentPositionMs: () -> Long,
    normalStyle: TextStyle,
    accompanimentStyle: TextStyle,
    subLine: String?,
    subLineStyle: TextStyle?,
    subReveal: () -> Float,
    onSeek: (Long) -> Unit,
) {
    // Lit = the focused row, or a duet row still being sung while the other
    // voice has already started. Flips only at those moments, so the row
    // recomposes twice per line instead of on every progress tick.
    val positionState by rememberUpdatedState(currentPositionMs)
    val lit by remember(group, index) {
        derivedStateOf {
            activeState.value == index || (
                group.endKnown && positionState().let { it >= group.startMs && it < group.endMs }
                )
        }
    }
    val side = if (!isStaged) LyricSide.Start else when (group.role) {
        VocalRole.DUET_RIGHT -> LyricSide.End
        VocalRole.UNISON_CENTER -> LyricSide.Center
        else -> LyricSide.Start
    }
    val horizontal = when (side) {
        LyricSide.Start -> Alignment.Start
        LyricSide.End -> Alignment.End
        LyricSide.Center -> Alignment.CenterHorizontally
    }
    val widthFraction = when {
        !isStaged -> 1f
        side == LyricSide.Center -> LYRIC_UNISON_WIDTH
        else -> LYRIC_DUET_WIDTH
    }
    // A solo song keeps each row in its own script direction; a staged duet
    // pins rows to absolute sides so a mixed-script row stays on its singer's
    // side.
    val textAlign = when {
        !isStaged -> TextAlign.Start
        side == LyricSide.Center -> TextAlign.Center
        (side == LyricSide.Start) != isOverallRtl -> TextAlign.Left
        else -> TextAlign.Right
    }
    val leadRtl = group.lead?.isRtl ?: group.backgrounds.firstOrNull()?.isRtl ?: isOverallRtl
    val pivotX = when (side) {
        LyricSide.Center -> 0.5f
        LyricSide.Start -> if ((if (isStaged) isOverallRtl else leadRtl)) 1f else 0f
        LyricSide.End -> if (isOverallRtl) 0f else 1f
    }
    val wordLit = lit && group.isWordSynced

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (group.stanzaGapAfter) LYRIC_STANZA_GAP else LYRIC_LINE_GAP)
            .graphicsLayer {
                val dist = kotlin.math.abs(index - focus())
                // A word-synced row being sung is always at full strength:
                // its dim base plus bright fill carry the contrast.
                val rowAlpha = if (wordLit) 1f else lyricFalloffAlpha(dist)
                alpha = if (browsing.value) maxOf(rowAlpha, 0.72f) else rowAlpha
                val scale = 1f - LYRIC_INACTIVE_SCALE_DROP * dist.coerceAtMost(1f)
                transformOrigin = TransformOrigin(pivotX, 0.5f)
                scaleX = scale
                scaleY = scale
                translationY = lyricTrailOffsetDp(index - activeState.value, trail()) * density
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onSeek(group.seekMs)
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = horizontal,
    ) {
        group.lead?.let { vocal ->
            KaraokeVocal(
                vocal = vocal,
                lit = lit,
                currentPositionMs = currentPositionMs,
                style = normalStyle,
                textAlign = textAlign,
                emphasis = 1f,
                glow = true,
                modifier = Modifier.fillMaxWidth(widthFraction),
            )
        }
        // Backing vocals nest under the lead: smaller, a little dimmer, and
        // part of the same item so they never take the focus.
        group.backgrounds.forEachIndexed { i, vocal ->
            KaraokeVocal(
                vocal = vocal,
                lit = lit,
                currentPositionMs = currentPositionMs,
                style = accompanimentStyle,
                textAlign = textAlign,
                emphasis = LYRIC_BACKING_EMPHASIS,
                glow = false,
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .padding(top = if (group.lead != null || i > 0) LYRIC_BACKING_SHIFT else 0.dp),
            )
        }
        if (!subLine.isNullOrBlank() && subLineStyle != null) {
            Box(Modifier.fillMaxWidth(widthFraction)) {
                LyricSubLine(
                    text = subLine,
                    style = subLineStyle,
                    isActive = lit,
                    reveal = subReveal,
                    textAlign = textAlign,
                )
            }
        }
    }
}

/**
 * Instrumental break: three dots that fill one after another across the
 * break, breathe while waiting and fade out right as the next line arrives.

 * The row collapses to zero height when the break is not playing, so it
 * never adds space between stanzas. Everything moving is read in layout and
 * draw lambdas; the frame loop only runs while the break is active.
 */
@Composable
internal fun LyricInterludeRow(
    item: LyricInterlude,
    index: Int,
    activeState: androidx.compose.runtime.State<Int>,
    isOverallRtl: Boolean,
    currentPositionMs: () -> Long,
) {
    val active by remember(index) { derivedStateOf { activeState.value == index } }
    val reveal = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(if (active) 420 else 280, easing = FastOutSlowInEasing),
        label = "lyricInterludeReveal",
    )
    val frameNanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            withFrameNanos { frameNanos.longValue = it }
        }
    }
    val side = when (item.role) {
        VocalRole.DUET_RIGHT -> LyricSide.End
        VocalRole.UNISON_CENTER -> LyricSide.Center
        else -> LyricSide.Start
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * reveal.value).roundToInt().coerceIn(0, placeable.height)
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(LYRIC_INTERLUDE_HEIGHT)
                .drawBehind {
                    val open = reveal.value
                    if (open <= 0.01f) return@drawBehind
                    val pos = currentPositionMs()
                    val span = (item.endMs - item.startMs).coerceAtLeast(1L).toFloat()
                    val progress = ((pos - item.startMs) / span).coerceIn(0f, 1f)
                    val seconds = frameNanos.longValue / 1_000_000_000.0
                    val breathe = 1f + 0.07f * kotlin.math.sin(seconds * 2.0 * Math.PI / 1.8).toFloat()
                    // Fade out over the last moments so the next line lands
                    // on an empty stage.
                    val exit = ((item.endMs - pos) / 450f).coerceIn(0f, 1f)
                    val radius = 4.5.dp.toPx()
                    val step = 17.dp.toPx()
                    val inset = 6.dp.toPx() + radius
                    val firstX = when (side) {
                        LyricSide.Center -> size.width / 2f - step
                        LyricSide.Start -> if (isOverallRtl) size.width - inset - 2 * step else inset
                        LyricSide.End -> if (isOverallRtl) inset else size.width - inset - 2 * step
                    }
                    val cy = size.height / 2f - 4.dp.toPx()
                    for (i in 0 until 3) {
                        // Fill in reading order.
                        val order = if (isOverallRtl) 2 - i else i
                        val f = (progress * 3f - order).coerceIn(0f, 1f)
                        val e = f * f * (3f - 2f * f)
                        val scale = (0.78f + 0.34f * e) * breathe * (0.55f + 0.45f * exit) * open
                        val alpha = ((0.30f + 0.70f * e) * open * exit).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = alpha),
                            radius = radius * scale,
                            center = Offset(firstX + step * i, cy),
                        )
                    }
                },
        )
    }
}

/**
 * Translated or romanized copy of a line, drawn underneath it. Height grows
 * from zero with the panel's shared reveal so the list reflows softly as
 * sub-lines arrive, while the text itself rides a slightly shorter tuck and

 * an alpha ramp (alpha = reveal², so the tail of the motion is a fade rather
 * than a bare height snap). Reads [reveal] per frame in layout and draw
 * phases; the panel animates one value for the whole list.
 */
@Composable
internal fun LyricSubLine(
    text: String,
    style: TextStyle,
    isActive: Boolean,
    reveal: () -> Float,
    textAlign: TextAlign = TextAlign.Start,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * reveal()).roundToInt().coerceIn(0, placeable.height)
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
    ) {
        val targetAlpha = if (isActive) 0.82f else 0.45f
        Text(
            text = text,
            style = style,
            color = Color.White.copy(alpha = targetAlpha),
            textAlign = textAlign,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .graphicsLayer {
                    val open = reveal()
                    alpha = open * open
                    translationY = -size.height * (1f - open) * 0.5f
                },
        )
    }
}

/**
 * Animated pixel scroll for [LazyListState], which only ships instant
 * [LazyListState.scrollBy] and indexed [LazyListState.animateScrollToItem].
 * A cubic-bezier frame loop so the active-line follow glides rather than
 * jumping. Callers already guard with runCatching. Mirrors the classic
 * view's helper (same package, file-private there so duplicated here).
 */
internal suspend fun LazyListState.animateScrollBy(pixels: Float, durationMs: Float = 560f) {
    if (pixels == 0f) return
    var consumed = 0f
    var startNanos = -1L
    var done = false
    while (!done) {
        val target = withFrameNanos { now ->
            if (startNanos < 0L) startNanos = now
            val t = ((now - startNanos) / (durationMs * 1_000_000f)).coerceIn(0f, 1f)
            done = t >= 1f
            pixels * LYRIC_EASE.transform(t)
        }
        val delta = target - consumed
        val scrolled = scrollBy(delta)
        consumed += scrolled
        if (scrolled == 0f && delta != 0f) {
            break
        }
    }
}

