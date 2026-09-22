package com.lastwave.app.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.local.LyricsAnimation
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.isRtlText
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.theme.LocalLiquidGlass
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val isWordSynced: Boolean = false,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
        val source: String? = null,
    ) : LyricsUiState {
        val isRtl: Boolean get() = lines.any { it.isRtl } || isRtlText(plainLyrics)
    }
    data object Empty : LyricsUiState
    data class Error(val message: String) : LyricsUiState
}

@Composable
fun LyricsPanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    progressState: StateFlow<PlaybackProgressState>? = null,
    lyricsAnimation: LyricsAnimation = LyricsAnimation.APPLE_FLUID,
    wavySeekbarEnabled: Boolean = true,
    onRetry: () -> Unit,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    val liquidGlass = LocalLiquidGlass.current

    // High-frequency live progress stream
    val progress by (progressState ?: player.progressState).collectAsStateWithLifecycle(
        initialValue = PlaybackProgressState(positionMs = state.positionMs, durationMs = state.durationMs),
    )

    // High-precision frame-level monotonic position clock for 60/120fps bit-perfect vocal sync
    var smoothedPositionMs by remember(track) { mutableLongStateOf(progress.positionMs) }

    LaunchedEffect(progress.positionMs, state.isPlaying) {
        val drift = kotlin.math.abs(smoothedPositionMs - progress.positionMs)
        // Hard snap on seek (>250ms drift) or when stopped/paused
        if (drift > 250 || !state.isPlaying) {
            smoothedPositionMs = progress.positionMs
        }
    }

    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        var lastFrameTime = SystemClock.elapsedRealtime()
        while (isActive) {
            withFrameMillis {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastFrameTime).coerceIn(0L, 50L)
                lastFrameTime = now

                val target = progress.positionMs
                val dur = progress.durationMs.takeIf { it > 0 } ?: state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE

                var nextPos = smoothedPositionMs + dt
                val drift = target - nextPos
                if (kotlin.math.abs(drift) > 250) {
                    nextPos = target
                } else {
                    nextPos += (drift * 0.15f).toLong()
                }
                smoothedPositionMs = nextPos.coerceAtLeast(smoothedPositionMs).coerceIn(0L, dur)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Weighted layout only: deliberately no card, background, border, or shadow.
        AnimatedContent(
            targetState = lyricsState,
            transitionSpec = {
                (fadeIn(tween(ExpressiveMotion.Quick)) +
                    androidx.compose.animation.scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(ExpressiveMotion.Quick)) +
                        androidx.compose.animation.scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.96f))
            },
            label = "lyricsStateContent",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { targetState ->
            when (targetState) {
                    is LyricsUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ExpressiveInlineLoadingIndicator(
                                    size = 42.dp,
                                    strokeWidth = 3.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Finding lyrics…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    is LyricsUiState.Empty, is LyricsUiState.Error -> {
                        EmptyLyricsView(
                            isInstrumental = false,
                            onRetry = onRetry,
                        )
                    }

                    is LyricsUiState.Success -> {
                        if (targetState.isInstrumental) {
                            EmptyLyricsView(
                                isInstrumental = true,
                                onRetry = onRetry,
                            )
                        } else if (targetState.isSynced && targetState.lines.isNotEmpty()) {
                            SyncedLyricsList(
                                lines = targetState.lines,
                                currentPositionMs = smoothedPositionMs,
                                isPlaying = state.isPlaying,
                                onSeek = player::seekTo,
                                animationStyle = lyricsAnimation,
                                liquidGlass = liquidGlass,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (!targetState.plainLyrics.isNullOrBlank()) {
                            PlainLyricsView(
                                plainLyrics = targetState.plainLyrics,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EmptyLyricsView(
                                isInstrumental = false,
                                onRetry = onRetry,
                            )
                        }
                    }

                    is LyricsUiState.Idle -> {
                        Box(Modifier.fillMaxSize())
                    }
            }
        }

        // Transparent playback controls; no separate player-bar container.
        LyricsPlaybackControls(
            state = state,
            currentPositionMs = smoothedPositionMs,
            totalDurationMs = if (progress.durationMs > 0) progress.durationMs else state.durationMs,
            player = player,
            wavySeekbarEnabled = wavySeekbarEnabled,
            onToggleFullscreen = onToggleFullscreen,
            isFullscreen = isFullscreen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp, top = 6.dp),
        )
    }
}

@Composable
private fun SyncedLyricsList(
    lines: List<LyricLine>,
    currentPositionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    animationStyle: LyricsAnimation,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var userScrolledTime by remember { mutableLongStateOf(0L) }

    val isOverallRtl = remember(lines) {
        val meaningfulLines = lines.filter { it.text.isNotBlank() && it.text != "♪" }
        if (meaningfulLines.isEmpty()) false
        else meaningfulLines.count { it.isRtl } > meaningfulLines.size / 2
    }

    // Active line detection: range-aware matching without per-frame derivedStateOf reallocations
    val activeIndex by remember(lines) {
        androidx.compose.runtime.derivedStateOf {
            val pos = currentPositionMs
            var match = -1
            for (idx in lines.indices.reversed()) {
                val line = lines[idx]
                val nextStart = lines.getOrNull(idx + 1)?.timeMs
                val effectiveDuration = when {
                    line.durationMs > 0 -> line.durationMs
                    line.syllables.isNotEmpty() -> {
                        val lastSyl = line.syllables.last()
                        (lastSyl.timeMs + lastSyl.durationMs - line.timeMs).coerceAtLeast(1000L)
                    }
                    nextStart != null && nextStart > line.timeMs -> {
                        val gap = nextStart - line.timeMs
                        if (gap <= 6000L) gap else 4500L
                    }
                    else -> 5000L
                }
                val end = line.timeMs + effectiveDuration
                if (pos >= line.timeMs && pos < end) {
                    match = idx
                    break
                }
            }
            if (match >= 0) match
            else lines.indexOfLast { it.timeMs <= pos }
        }
    }

    if (listState.isScrollInProgress) {
        userScrolledTime = System.currentTimeMillis()
    }

    LaunchedEffect(activeIndex, isPlaying) {
        val timeSinceUserScroll = System.currentTimeMillis() - userScrolledTime
        if (timeSinceUserScroll > 2200L && activeIndex in lines.indices) {
            runCatching {
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                val targetItem = layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                if (targetItem != null && viewportHeight > 0) {
                    val targetY = viewportHeight / 3
                    val delta = targetItem.offset - targetY
                    listState.animateScrollBy(delta.toFloat())
                } else {
                    val targetIndex = (activeIndex - 1).coerceAtLeast(0)
                    listState.animateScrollToItem(index = targetIndex, scrollOffset = 0)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.clipToBounds(),
        contentPadding = PaddingValues(
            top = 40.dp,
            bottom = 130.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(
            when (animationStyle) {
                LyricsAnimation.APPLE_ZOOM -> 22.dp
                LyricsAnimation.CARD_POP -> 16.dp
                else -> 18.dp
            },
        ),
    ) {
        itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
            val isActive = index == activeIndex
            val isPast = activeIndex >= 0 && index < activeIndex
            val distance = kotlin.math.abs(index - activeIndex)
            val isLineRtl = remember(line, isOverallRtl) {
                line.isRtl || (isOverallRtl && (line.text.isBlank() || line.text == "♪"))
            }

            // Each profile gets a distinct motion signature. These targets only
            // change when focus changes (except the short onset pulse below), so
            // off-screen/inactive rows never run a permanent animation loop.
            val scaleTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> if (isActive) 1.085f else if (distance == 1) 0.99f else 0.975f
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) 1.10f else if (distance == 1) 0.99f else 0.97f
                LyricsAnimation.KINETIC_SLIDE -> if (isActive) 1.045f else if (isPast) 0.99f else 0.975f
                LyricsAnimation.CINEMATIC_BLUR -> if (isActive) 1.065f else if (distance == 1) 0.96f else 0.93f
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) 1.075f else if (distance == 1) 0.99f else 0.97f
                LyricsAnimation.CARD_POP -> if (isActive) 1.065f else 0.985f
                LyricsAnimation.APPLE_ZOOM -> when {
                    isActive -> 1.18f
                    distance == 1 -> 0.94f
                    else -> 0.88f
                }
                LyricsAnimation.MINIMAL_WAVE -> 1f
            }

            val scaleSpec: AnimationSpec<Float> = when (animationStyle) {
                LyricsAnimation.KARAOKE_PULSE -> spring(
                    dampingRatio = 0.62f,
                    stiffness = Spring.StiffnessLow,
                )
                LyricsAnimation.APPLE_FLUID, LyricsAnimation.APPLE_ZOOM -> spring(
                    dampingRatio = 0.74f,
                    stiffness = Spring.StiffnessMediumLow,
                )
                LyricsAnimation.CARD_POP -> spring(
                    dampingRatio = 0.68f,
                    stiffness = Spring.StiffnessMedium,
                )
                LyricsAnimation.MINIMAL_WAVE -> tween(100)
                else -> spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            }

            val scale by animateFloatAsState(
                targetValue = scaleTarget,
                animationSpec = scaleSpec,
                label = "lyricScale_$index",
            )

            // A short, position-locked vocal onset pulse. It settles cleanly
            // when paused and does not need an infinite transition clock.
            val onsetElapsedMs = (currentPositionMs - line.timeMs).coerceAtLeast(0L)
            val onsetPhase = (onsetElapsedMs / 520f).coerceIn(0f, 1f)
            val onsetWave = if (isActive && isPlaying && onsetPhase < 1f) {
                kotlin.math.sin(Math.PI.toFloat() * onsetPhase)
            } else 0f
            val pulseScale = when (animationStyle) {
                LyricsAnimation.KARAOKE_PULSE -> 1f + 0.045f * onsetWave
                LyricsAnimation.APPLE_FLUID -> 1f + 0.014f * onsetWave
                LyricsAnimation.LOSSLESS_GLOW -> 1f + 0.010f * onsetWave
                else -> 1f
            }

            // Horizontal focus tracking / directional entry and exit.
            // Inverted for RTL so lyrics smoothly glide along reading orientation.
            val rawTranslationXTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> when {
                    isActive -> 4f
                    isPast -> 0f
                    else -> -5f
                }
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) 3f else 0f
                LyricsAnimation.KINETIC_SLIDE -> when {
                    isActive -> 0f
                    isPast -> 12f
                    else -> -24f
                }
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) 2f else 0f
                LyricsAnimation.MINIMAL_WAVE -> when {
                    isActive -> 2f
                    isPast -> 0f
                    else -> -2f
                }
                else -> 0f
            }
            val translationXTarget = if (isLineRtl) -rawTranslationXTarget else rawTranslationXTarget
            val translationX by animateFloatAsState(
                targetValue = translationXTarget,
                animationSpec = when (animationStyle) {
                    LyricsAnimation.KINETIC_SLIDE -> spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
                    LyricsAnimation.APPLE_FLUID -> spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow)
                    LyricsAnimation.MINIMAL_WAVE -> tween(110)
                    else -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                },
                label = "lyricTransX_$index",
            )

            // Vertical depth drift gives past/future lines a readable direction.
            val translationYTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> when {
                    isActive -> -2f
                    isPast -> -1f
                    else -> 3f
                }
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) -2f else 1f
                LyricsAnimation.KINETIC_SLIDE -> if (isActive) -1f else 1f
                LyricsAnimation.CINEMATIC_BLUR -> when {
                    isActive -> 0f
                    isPast -> -10f
                    else -> 10f
                }
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) -2f else 1f
                LyricsAnimation.CARD_POP -> if (isActive) -4f else 2f
                LyricsAnimation.APPLE_ZOOM -> when {
                    isActive -> -3f
                    isPast -> -1f
                    else -> 2f
                }
                LyricsAnimation.MINIMAL_WAVE -> if (isPast) -1f else if (isActive) 0f else 1f
            }
            val translationY by animateFloatAsState(
                targetValue = translationYTarget,
                animationSpec = when (animationStyle) {
                    LyricsAnimation.CINEMATIC_BLUR -> spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessLow)
                    LyricsAnimation.CARD_POP -> spring(dampingRatio = 0.70f, stiffness = Spring.StiffnessMediumLow)
                    LyricsAnimation.MINIMAL_WAVE -> tween(100)
                    else -> spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium)
                },
                label = "lyricTransY_$index",
            )

            val rawRotationTarget = when (animationStyle) {
                LyricsAnimation.KINETIC_SLIDE -> when {
                    isActive -> 0f
                    isPast -> 0.35f
                    else -> -0.65f
                }
                LyricsAnimation.CARD_POP -> when {
                    isActive -> 0f
                    isPast -> -0.35f
                    else -> 0.55f
                }
                else -> 0f
            }
            val rotationTarget = if (isLineRtl) -rawRotationTarget else rawRotationTarget
            val rotation by animateFloatAsState(
                targetValue = rotationTarget,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                label = "lyricRotation_$index",
            )
            val depthRotationTarget = when (animationStyle) {
                LyricsAnimation.CINEMATIC_BLUR -> when {
                    isActive -> 0f
                    isPast -> -1.25f
                    else -> 1.25f
                }
                LyricsAnimation.CARD_POP -> when {
                    isActive -> 0f
                    isPast -> -0.6f
                    else -> 0.8f
                }
                else -> 0f
            }
            val depthRotation by animateFloatAsState(
                targetValue = depthRotationTarget,
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                label = "lyricDepth_$index",
            )

            // Alpha Floor
            val alphaTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> if (isActive) 1f else if (distance == 1) 0.64f else if (isPast) 0.50f else 0.43f
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) 1f else if (isPast) 0.62f else 0.49f
                LyricsAnimation.KINETIC_SLIDE -> if (isActive) 1f else if (isPast) 0.54f else 0.42f
                LyricsAnimation.CINEMATIC_BLUR -> if (isActive) 1f else if (distance <= 1) 0.58f else 0.28f
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) 1f else if (distance == 1) 0.66f else 0.46f
                LyricsAnimation.CARD_POP -> if (isActive) 1f else if (isPast) 0.62f else 0.48f
                LyricsAnimation.APPLE_ZOOM -> if (isActive) 1f else if (distance == 1) 0.55f else 0.32f
                LyricsAnimation.MINIMAL_WAVE -> if (isActive) 1f else if (distance == 1) 0.58f else 0.38f
            }
            val alpha by animateFloatAsState(
                targetValue = alphaTarget,
                animationSpec = tween(if (animationStyle == LyricsAnimation.MINIMAL_WAVE) 90 else 160),
                label = "lyricAlpha_$index",
            )

            val interactiveColor = Color.White
            val textColor by animateColorAsState(
                targetValue = Color.White,
                animationSpec = tween(140),
                label = "lyricColor_$index",
            )

            val lineLayoutDirection = if (isLineRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale * pulseScale
                            scaleY = scale * pulseScale
                            this.alpha = alpha
                            this.translationX = translationX * density
                            this.translationY = translationY * density
                            rotationZ = rotation
                            rotationX = depthRotation
                            if (depthRotation != 0f) cameraDistance = 24f * density
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onSeek(line.timeMs)
                        }
                        .padding(
                            horizontal = if (animationStyle == LyricsAnimation.CARD_POP) 16.dp else 12.dp,
                            vertical = if (isActive) 10.dp else 8.dp,
                        ),
                ) {
                    val fontStyle = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = if (isActive) {
                            if (animationStyle == LyricsAnimation.APPLE_ZOOM) FontWeight.Black else FontWeight.ExtraBold
                        } else FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        lineHeight = 32.sp,
                    )

                    WordByWordLyricLine(
                        line = line,
                        currentPositionMs = currentPositionMs,
                        isActive = isActive,
                        activeColor = textColor,
                        inactiveColor = Color.White.copy(alpha = 0.55f),
                        liquidGlass = liquidGlass,
                        accentColor = interactiveColor,
                        animationStyle = animationStyle,
                        fontStyle = fontStyle,
                        isRtl = isLineRtl,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordByWordLyricLine(
    line: LyricLine,
    currentPositionMs: Long,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    liquidGlass: Boolean,
    accentColor: Color,
    animationStyle: LyricsAnimation,
    fontStyle: TextStyle,
    isRtl: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val lineLayoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val lineColor by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        animationSpec = tween(220),
        label = "lineColor",
    )
    CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
        if (!line.hasSyllables || !isActive) {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = fontStyle,
                    color = lineColor,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!line.transliteration.isNullOrBlank() && isActive) {
                    val transliterationRtl = isRtlText(line.transliteration)
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (transliterationRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        Text(
                            text = line.transliteration,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.2.sp,
                            ),
                            color = activeColor.copy(alpha = 0.72f),
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp),
                        )
                    }
                }
            }
            return@CompositionLocalProvider
        }

        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.Start,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Providers store each word trimmed — without a visual
                // separator FlowRow renders "Allthatglittersisgold". The
                // space is display-only (no timing change). Skip it for
                // spaceless (CJK) lines, when a provider already kept
                // spacing (e.g. Kugou KRC trailing spaces), and before
                // continuation fragments (Apple Music `part` words).
                val needsSpacing = line.text.contains(' ') || line.text.contains('\u00A0')
                line.syllables.forEachIndexed { sIndex, syllable ->
                    val sylStart = syllable.timeMs
                    val minDur = if (syllable.durationMs > 0) syllable.durationMs else 150L
                    val sylEnd = sylStart + minDur
                    val isSyllableActive = currentPositionMs in sylStart until sylEnd
                    val isSyllablePast = currentPositionMs >= sylEnd

                    val nextSyllable = line.syllables.getOrNull(sIndex + 1)
                    val separator = if (needsSpacing &&
                        sIndex < line.syllables.lastIndex &&
                        !syllable.text.endsWith(' ') &&
                        !syllable.text.endsWith('\u00A0') &&
                        nextSyllable?.appendToPrevious != true &&
                        (nextSyllable == null || (!nextSyllable.text.startsWith(' ') && !nextSyllable.text.startsWith('\u00A0')))
                    ) " " else ""
                    val displayText = syllable.text + separator

                    val sylScaleTarget = if (isSyllableActive) {
                        when (animationStyle) {
                            LyricsAnimation.APPLE_FLUID -> 1.08f
                            LyricsAnimation.KARAOKE_PULSE -> 1.13f
                            LyricsAnimation.KINETIC_SLIDE -> 1.07f
                            LyricsAnimation.CINEMATIC_BLUR -> 1.05f
                            LyricsAnimation.LOSSLESS_GLOW -> 1.09f
                            LyricsAnimation.CARD_POP -> 1.07f
                            LyricsAnimation.APPLE_ZOOM -> 1.11f
                            LyricsAnimation.MINIMAL_WAVE -> 1.02f
                        }
                    } else 1f
                    val sylScale by animateFloatAsState(
                        targetValue = sylScaleTarget,
                        animationSpec = when (animationStyle) {
                            LyricsAnimation.KARAOKE_PULSE -> spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)
                            LyricsAnimation.APPLE_FLUID, LyricsAnimation.APPLE_ZOOM -> spring(
                                dampingRatio = 0.72f,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                            LyricsAnimation.MINIMAL_WAVE -> tween(70)
                            else -> spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                        },
                        label = "sylScale_${sIndex}",
                    )

                    val sylLiftTarget = if (isSyllableActive) {
                        when (animationStyle) {
                            LyricsAnimation.KARAOKE_PULSE, LyricsAnimation.CARD_POP, LyricsAnimation.APPLE_ZOOM -> -3f
                            LyricsAnimation.APPLE_FLUID, LyricsAnimation.KINETIC_SLIDE, LyricsAnimation.LOSSLESS_GLOW -> -2f
                            LyricsAnimation.CINEMATIC_BLUR -> -1f
                            LyricsAnimation.MINIMAL_WAVE -> 0f
                        }
                    } else 0f
                    val sylLift by animateFloatAsState(
                        targetValue = sylLiftTarget,
                        animationSpec = if (animationStyle == LyricsAnimation.MINIMAL_WAVE) {
                            tween(70)
                        } else {
                            spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow)
                        },
                        label = "sylLift_${sIndex}",
                    )

                    val sylAlphaTarget = when {
                        isSyllableActive -> 1.0f
                        isSyllablePast -> 0.94f
                        else -> when (animationStyle) {
                            LyricsAnimation.CINEMATIC_BLUR -> 0.28f
                            LyricsAnimation.APPLE_ZOOM -> 0.34f
                            LyricsAnimation.MINIMAL_WAVE -> 0.52f
                            else -> 0.44f
                        }
                    }
                    val sylAlpha by animateFloatAsState(
                        targetValue = sylAlphaTarget,
                        animationSpec = tween(90),
                        label = "sylAlpha_${sIndex}",
                    )

                    val sylColor by animateColorAsState(
                        targetValue = when {
                            isSyllableActive -> if (liquidGlass) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                accentColor
                            }
                            isSyllablePast -> activeColor
                            else -> inactiveColor.copy(alpha = 0.44f)
                        },
                        animationSpec = tween(90),
                        label = "sylColor_${sIndex}",
                    )

                    Text(
                        text = displayText,
                        style = fontStyle,
                        color = sylColor,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = sylScale
                                scaleY = sylScale
                                translationY = sylLift * density
                                alpha = sylAlpha
                            },
                    )
                }
            }

            if (!line.transliteration.isNullOrBlank()) {
                val transliterationRtl = isRtlText(line.transliteration)
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (transliterationRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Text(
                        text = line.transliteration,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp,
                        ),
                        color = activeColor.copy(alpha = 0.76f),
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlainLyricsView(
    plainLyrics: String,
    modifier: Modifier = Modifier,
) {
    val isRtl = remember(plainLyrics) { isRtlText(plainLyrics) }
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier
                .verticalScroll(scrollState)
                .padding(top = 24.dp, bottom = 90.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.SyncDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White.copy(alpha = 0.90f),
                )
                Text(
                    "Lyrics not time-synced",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.70f),
                )
            }

            Text(
                text = plainLyrics,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 19.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.1.sp,
                ),
                textAlign = TextAlign.Start,
                color = Color.White.copy(alpha = 0.94f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyLyricsView(
    isInstrumental: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = if (isInstrumental) Icons.Filled.MusicOff else Icons.Filled.Lyrics,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = Color.White.copy(alpha = 0.90f),
            )

            Text(
                text = if (isInstrumental) "Instrumental" else "No lyrics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            Text(
                text = if (isInstrumental) {
                    "This track has no vocal lyrics."
                } else {
                    "No synced lyrics found for this track."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )

            if (!isInstrumental) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = onRetry,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun LyricsPlaybackControls(
    state: MusicPlayerState,
    currentPositionMs: Long,
    totalDurationMs: Long,
    player: MusicPlayer,
    wavySeekbarEnabled: Boolean = true,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // This Column performs layout only. It intentionally draws no container.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onToggleFullscreen != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val playerInteraction = remember { MutableInteractionSource() }
                val isPlayerPressed by playerInteraction.collectIsPressedAsState()
                val playerScale by animateFloatAsState(
                    targetValue = if (isPlayerPressed) 0.82f else 1.0f,
                    animationSpec = ExpressiveMotion.spatialSpring(),
                    label = "playerTabScale",
                )
                LiquidGlassSurface(
                    glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, interactionSource = playerInteraction),
                    onClick = onToggleFullscreen,
                    interactionSource = playerInteraction,
                    shape = CircleShape,
                    color = liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                    contentColor = Color.White.copy(alpha = 0.90f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .size(46.dp)
                        .graphicsLayer {
                            scaleX = playerScale
                            scaleY = playerScale
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (isFullscreen) "Exit fullscreen lyrics" else "Fullscreen lyrics",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        if (isFullscreen) return@Column

        // Current-gesture value only; null = finger off, show live position.
        // Keyed by track so a previous song's drag can never leak into this
        // one, and nullable so a press without movement seeks nowhere while a
        // gesture that ends without onValueChangeFinished can't pin the bar.
        val lyricsTrackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" }
        val seekInteraction = remember(lyricsTrackKey) { MutableInteractionSource() }
        val frameworkDragging by seekInteraction.collectIsDraggedAsState()
        var dragValue by remember(lyricsTrackKey) { mutableStateOf<Float?>(null) }
        LaunchedEffect(frameworkDragging, lyricsTrackKey) {
            if (!frameworkDragging) dragValue = null
        }
        val end = totalDurationMs.coerceAtLeast(1).toFloat()
        val shown = (dragValue ?: currentPositionMs.coerceIn(0, totalDurationMs.coerceAtLeast(0)).toFloat())
            .coerceIn(0f, end)

        if (wavySeekbarEnabled) {
            WavySeekBar(
                positionMs = currentPositionMs,
                durationMs = totalDurationMs,
                isPlaying = state.isPlaying,
                onSeek = player::seekTo,
                isTranslucent = true,
                trackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" },
                showTimeLabels = false,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PlayerProgressSlider(
                value = shown,
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    // Commit only this gesture's value; no value = no seek.
                    val target = dragValue?.toLong()
                    dragValue = null
                    if (target != null) player.seekTo(target)
                },
                valueRange = 0f..end,
                enabled = totalDurationMs > 0,
                modifier = Modifier.fillMaxWidth(),
                interactionSource = seekInteraction,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatTime(shown.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = player::previous,
                    modifier = Modifier
                        .size(42.dp)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current)
                        .clip(CircleShape)
                        .background(liquidGlassContainerColor(Color.White.copy(alpha = 0.14f))),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        "Previous",
                        Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = 0.94f),
                    )
                }

                LiquidGlassSurface(
                    glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current),
                    onClick = player::togglePlayPause,
                    shape = CircleShape,
                    color = liquidGlassContainerColor(Color.White),
                    contentColor = Color.Black,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.isBuffering) {
                            ExpressiveInlineLoadingIndicator(
                                size = 22.dp,
                                color = Color.Black,
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(28.dp))
                        }
                    }
                }

                IconButton(
                    onClick = player::next,
                    modifier = Modifier
                        .size(42.dp)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current)
                        .clip(CircleShape)
                        .background(liquidGlassContainerColor(Color.White.copy(alpha = 0.14f))),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        "Next",
                        Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = 0.94f),
                    )
                }
            }

            Text(
                "−${formatTime((totalDurationMs - shown.toLong()).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Animated pixel scroll for [LazyListState], which only ships instant
 * [LazyListState.scrollBy] and indexed [LazyListState.animateScrollToItem].
 * Ease-out-cubic frame loop so the active-line follow stays smooth instead
 * of jumping. Callers already guard with runCatching.
 */
private suspend fun LazyListState.animateScrollBy(pixels: Float) {
    if (pixels == 0f) return
    var consumed = 0f
    var startNanos = -1L
    var done = false
    while (!done) {
        val target = withFrameNanos { now ->
            if (startNanos < 0L) startNanos = now
            val t = ((now - startNanos) / 350_000_000f).coerceIn(0f, 1f)
            done = t >= 1f
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            pixels * eased
        }
        val delta = target - consumed
        consumed += delta - scrollBy(delta)
    }
}
