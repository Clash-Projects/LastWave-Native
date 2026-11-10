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

@Composable
internal fun SyncedLyricsProgressHost(
    lines: List<com.lastwave.app.data.lyrics.LyricLine>,
    isOverallRtl: Boolean,
    normalStyle: TextStyle,
    accompanimentStyle: TextStyle,
    progressFlow: StateFlow<PlaybackProgressState>,
    progressInitial: PlaybackProgressState,
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsOffsetMs: Long = 0L,
    subLines: List<String?>? = null,
    subReveal: () -> Float = { 0f },
    subLineStyle: TextStyle? = null,
    displayMode: LyricsDisplayMode = LyricsDisplayMode.Original,
    modifier: Modifier = Modifier,
) {
    // The ~60ms playhead samples are kept in a state that is only read from
    // the frame clock / snapshotFlow below, never during composition, so a
    // progress tick no longer recomposes this host and re-runs the list.
    val latestSample = remember { mutableLongStateOf(progressInitial.positionMs) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(progressFlow, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            progressFlow.collect { latestSample.longValue = it.positionMs }
        }
    }
    val offsetState by rememberUpdatedState(lyricsOffsetMs)
    val framePositionMs = remember { mutableLongStateOf(progressInitial.positionMs) }
    // Vsync clock (90/120Hz): advances by real frame time and is steered
    // toward the ExoPlayer playhead (sampled every ~60ms). Never runs
    // backwards on normal drift; snaps instantly on seeks/track changes.
    // Line-synced lyrics only change focus at line starts, so they do not need
    // a 90/120Hz frame loop (which kept the main thread and GPU awake for the
    // whole song). They follow each playhead sample and additionally wake
    // exactly on the next line boundary, so focus still moves on time.
    // Word-synced lyrics keep the per-frame clock for the karaoke wipe.
    val stage = remember(lines) { buildLyricsStage(lines) }
    val lineSyncedOnly = remember(stage) {
        stage.items.none { it is LyricLineGroup && it.isWordSynced }
    }
    LaunchedEffect(state.isPlaying, lineSyncedOnly, if (lineSyncedOnly) stage else null) {
        if (!state.isPlaying) {
            snapshotFlow { latestSample.longValue }.collect { framePositionMs.longValue = it }
            return@LaunchedEffect
        }
        if (lineSyncedOnly) {
            snapshotFlow { latestSample.longValue }.collectLatest { sample ->
                // Like the frame clock: never step back on small jitter
                // (a boundary wake may run a few ms ahead of the next
                // sample), but follow seeks immediately.
                val shown = framePositionMs.longValue
                framePositionMs.longValue = if (sample < shown && shown - sample <= 300L) shown else sample
                val shifted = sample + offsetState
                val nextStart = stage.nextStartAfter(shifted) ?: return@collectLatest
                val waitMs = nextStart - shifted
                if (waitMs in 1L..LINE_BOUNDARY_WAKE_WINDOW_MS) {
                    delay(waitMs)
                    framePositionMs.longValue = sample + waitMs
                }
            }
            return@LaunchedEffect
        }
        var lastSample = latestSample.longValue
        var sampleFrameNanos = -1L
        var lastFrameNanos = -1L
        var display = latestSample.longValue.toDouble()
        while (true) {
            withFrameNanos { now ->
                val sample = latestSample.longValue
                if (sample != lastSample || sampleFrameNanos < 0L) {
                    lastSample = sample
                    sampleFrameNanos = now
                }
                val target = sample + ((now - sampleFrameNanos) / 1_000_000.0).coerceAtMost(250.0)
                val frameDt = if (lastFrameNanos < 0L) 0.0 else (now - lastFrameNanos) / 1_000_000.0
                lastFrameNanos = now
                val predicted = display + frameDt
                val err = target - predicted
                display = if (kotlin.math.abs(err) > 300.0) {
                    target
                } else {
                    maxOf(display, predicted + err * 0.2)
                }
                framePositionMs.longValue = display.toLong()
            }
        }
    }
    val currentPosition: () -> Long = remember {
        { framePositionMs.longValue + offsetState }
    }
    ModernSyncedLyricsList(
        stage = stage,
        sourceLines = lines,
        isOverallRtl = isOverallRtl,
        normalStyle = normalStyle,
        accompanimentStyle = accompanimentStyle,
        currentPositionMs = currentPosition,
        isPlaying = state.isPlaying,
        onSeek = { lineTimeMs ->
            // Inverse of the highlight shift: tap targets audio time.
            player.seekTo((lineTimeMs - lyricsOffsetMs).coerceAtLeast(0L))
        },
        subLines = subLines,
        subReveal = subReveal,
        subLineStyle = subLineStyle,
        displayMode = displayMode,
        modifier = modifier,
    )
}

/**

 * Leaf host for transport controls: owns the 60ms seekbar clock so the panel
 * above stays static. No behavior change, strictly fewer recompositions.
 */
@Composable
internal fun ModernLyricsControlsHost(
    state: MusicPlayerState,
    progressFlow: StateFlow<PlaybackProgressState>,
    progressInitial: PlaybackProgressState,
    player: MusicPlayer,
    wavySeekbarEnabled: Boolean = true,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    lyricsOffsetMs: Long = 0L,
    onOpenLyricsOffset: (() -> Unit)? = null,
    controlsVisible: Boolean = true,
    displayMode: LyricsDisplayMode = LyricsDisplayMode.Original,
    translationLoading: Boolean = false,
    romanizationLoading: Boolean = false,
    translationAvailable: Boolean = false,
    onToggleTranslation: () -> Unit = {},
    onToggleRomanization: () -> Unit = {},
    showFontSlider: Boolean = false,
    onShowFontSliderChange: (Boolean) -> Unit = {},
    onOpenLyricsSource: (() -> Unit)? = null,
    lyricsFontScale: Float = 1f,
    onLyricsFontScaleChange: (Float) -> Unit = {},
    onLyricsFontScalePreview: (Float) -> Unit = {},
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    canShareLyrics: Boolean = false,
    onShareLyrics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = progressInitial)
    ModernLyricsControls(
        state = state,
        currentPositionMs = progress.positionMs,
        totalDurationMs = if (progress.durationMs > 0) progress.durationMs else state.durationMs,
        player = player,
        wavySeekbarEnabled = wavySeekbarEnabled,
        onToggleFullscreen = onToggleFullscreen,
        isFullscreen = isFullscreen,
        lyricsOffsetMs = lyricsOffsetMs,
        onOpenLyricsOffset = onOpenLyricsOffset,
        controlsVisible = controlsVisible,
        displayMode = displayMode,
        translationLoading = translationLoading,
        romanizationLoading = romanizationLoading,
        translationAvailable = translationAvailable,
        onToggleTranslation = onToggleTranslation,
        onToggleRomanization = onToggleRomanization,
        showFontSlider = showFontSlider,
        onShowFontSliderChange = onShowFontSliderChange,
        onOpenLyricsSource = onOpenLyricsSource,
        lyricsFontScale = lyricsFontScale,
        onLyricsFontScaleChange = onLyricsFontScaleChange,
        onLyricsFontScalePreview = onLyricsFontScalePreview,
        primaryColor = primaryColor,
        secondaryColor = secondaryColor,
        tertiaryColor = tertiaryColor,
        canShareLyrics = canShareLyrics,
        onShareLyrics = onShareLyrics,
        modifier = modifier,
    )
}


@Composable
internal fun ModernLyricsControls(
    state: MusicPlayerState,
    currentPositionMs: Long,
    totalDurationMs: Long,
    player: MusicPlayer,
    wavySeekbarEnabled: Boolean = true,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    lyricsOffsetMs: Long = 0L,
    onOpenLyricsOffset: (() -> Unit)? = null,
    controlsVisible: Boolean = true,
    displayMode: LyricsDisplayMode = LyricsDisplayMode.Original,
    translationLoading: Boolean = false,
    romanizationLoading: Boolean = false,
    translationAvailable: Boolean = false,
    onToggleTranslation: () -> Unit = {},
    onToggleRomanization: () -> Unit = {},
    showFontSlider: Boolean = false,
    onShowFontSliderChange: (Boolean) -> Unit = {},
    onOpenLyricsSource: (() -> Unit)? = null,
    lyricsFontScale: Float = 1f,
    onLyricsFontScaleChange: (Float) -> Unit = {},
    onLyricsFontScalePreview: (Float) -> Unit = {},
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    canShareLyrics: Boolean = false,
    onShareLyrics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sliderPosition by remember(lyricsFontScale) { mutableFloatStateOf(lyricsFontScale) }
    var isDragging by remember { mutableStateOf(false) }
    val currentDisplayScale = if (isDragging) sliderPosition else lyricsFontScale
    val buttonsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(150),
        label = "lyricsButtonsAlpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = showFontSlider,
            enter = fadeIn(tween(150)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Lyrics size: ${(currentDisplayScale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.90f),
                        )
                        if (currentDisplayScale != 1.0f) {
                            TextButton(
                                onClick = {
                                    sliderPosition = 1.0f
                                    isDragging = false
                                    onLyricsFontScalePreview(1.0f)
                                    onLyricsFontScaleChange(1.0f)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp),
                            ) {
                                Text(
                                    "Reset",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "A",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.70f),
                        )
                        Slider(
                            value = currentDisplayScale,
                            onValueChange = {
                                isDragging = true
                                sliderPosition = it
                                onLyricsFontScalePreview(it)
                            },
                            onValueChangeFinished = {
                                isDragging = false
                                onLyricsFontScaleChange(sliderPosition)
                            },
                            valueRange = 0.7f..1.5f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "A",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.95f),
                        )
                    }
                }
            }
        }

        var showLyricsOptions by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = buttonsAlpha }
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onToggleFullscreen != null) {
                val playerInteraction = remember { MutableInteractionSource() }
                val isPlayerPressed by playerInteraction.collectIsPressedAsState()
                val playerScale by animateFloatAsState(
                    targetValue = if (isPlayerPressed) 0.82f else 1.0f,
                    animationSpec = ExpressiveMotion.spatialSpring(),
                    label = "playerTabScale",
                )
                IconButton(
                    onClick = onToggleFullscreen,
                    enabled = controlsVisible,
                    interactionSource = playerInteraction,
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            scaleX = playerScale
                            scaleY = playerScale
                        }
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = playerInteraction)
                        .background(
                            liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                        ),
                ) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit fullscreen lyrics" else "Fullscreen lyrics",
                        modifier = Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = 0.90f),
                    )
                }

                Spacer(Modifier.width(10.dp))
            }

            // 3-dots button placed right AFTER the fullscreen button
            val moreInteraction = remember { MutableInteractionSource() }
            val isMorePressed by moreInteraction.collectIsPressedAsState()
            val moreScale by animateFloatAsState(
                targetValue = if (isMorePressed) 0.82f else 1.0f,
                animationSpec = ExpressiveMotion.spatialSpring(),
                label = "moreBtnScale",
            )
            IconButton(
                onClick = { showLyricsOptions = true },
                enabled = controlsVisible,
                interactionSource = moreInteraction,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = moreScale
                        scaleY = moreScale
                    }
                    .clip(CircleShape)
                    .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = moreInteraction)
                    .background(
                        liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                    ),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Lyrics options",
                    modifier = Modifier.size(24.dp),
                    tint = Color.White.copy(alpha = 0.90f),
                )
            }
        }

        if (showLyricsOptions) {
            LyricsOptionsMenuDialog(
                onDismiss = { showLyricsOptions = false },
                onOpenLyricsSource = onOpenLyricsSource,
                canShareLyrics = canShareLyrics,
                onShareLyrics = onShareLyrics,
                lyricsFontScale = lyricsFontScale,
                onLyricsFontScaleChange = onLyricsFontScaleChange,
                onLyricsFontScalePreview = onLyricsFontScalePreview,
                displayMode = displayMode,
                translationAvailable = translationAvailable,
                onToggleTranslation = onToggleTranslation,
                onToggleRomanization = onToggleRomanization,
                lyricsOffsetMs = lyricsOffsetMs,
                onOpenLyricsOffset = onOpenLyricsOffset,
            )
        }

        if (isFullscreen) return@Column

        // Current-gesture value only; null = finger off, show live position.
        // Keyed by track so a previous song's drag can never leak into this
        // one, and nullable so a press without movement seeks nowhere while a
        // gesture that ends without onValueChangeFinished can't pin the bar.
        val lyricsTrackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" }
        val seekInteraction = remember(lyricsTrackKey) { MutableInteractionSource() }
        var isInteracting by remember(lyricsTrackKey) { mutableStateOf(false) }
        LaunchedEffect(seekInteraction, lyricsTrackKey) {
            var dragCount = 0
            var pressCount = 0
            seekInteraction.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> dragCount++
                    is DragInteraction.Stop, is DragInteraction.Cancel -> dragCount = maxOf(0, dragCount - 1)
                    is PressInteraction.Press -> pressCount++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressCount = maxOf(0, pressCount - 1)
                }
                isInteracting = dragCount > 0 || pressCount > 0
            }
        }
        var dragValue by remember(lyricsTrackKey) { mutableStateOf<Float?>(null) }
        var lastSeekValue by remember(lyricsTrackKey) { mutableStateOf<Float?>(null) }
        LaunchedEffect(isInteracting, lyricsTrackKey) {
            if (!isInteracting) {
                delay(120L)
                dragValue = null
                lastSeekValue = null
            }
        }
        LaunchedEffect(dragValue, isInteracting, lyricsTrackKey) {
            if (dragValue != null && !isInteracting) {
                delay(250L)
                dragValue = null
                lastSeekValue = null
            }
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
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                tertiaryColor = tertiaryColor,
            )
        } else {
            PlayerProgressSlider(
                value = shown,
                onValueChange = {
                    dragValue = it
                    lastSeekValue = it
                },
                onValueChangeFinished = {
                    // Commit only this gesture's value; no value = no seek.
                    val target = (lastSeekValue ?: dragValue)?.toLong()
                    dragValue = null
                    lastSeekValue = null
                    if (target != null) player.seekTo(target)
                },
                valueRange = 0f..end,
                enabled = totalDurationMs > 0,
                modifier = Modifier.fillMaxWidth(),
                interactionSource = seekInteraction,
                primaryColor = primaryColor,
                tertiaryColor = tertiaryColor,
            )
        }

        val currentSecond = shown.toLong() / 1000L
        val remainingSecond = (totalDurationMs - shown.toLong()).coerceAtLeast(0) / 1000L
        val formattedElapsed = remember(currentSecond) { formatTime(currentSecond * 1000L) }
        val formattedRemaining = remember(remainingSecond) { "\u2212${formatTime(remainingSecond * 1000L)}" }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formattedElapsed,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val prevInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = player::previous,
                    interactionSource = prevInteraction,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = prevInteraction)
                        .background(liquidGlassContainerColor(Color.White.copy(alpha = 0.14f))),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        "Previous",
                        Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = 0.94f),
                    )
                }

                val playInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = player::togglePlayPause,
                    interactionSource = playInteraction,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = playInteraction)
                        .background(liquidGlassContainerColor(Color.White.copy(alpha = 0.18f))),
                ) {
                    if (state.isBuffering) {
                        ExpressiveInlineLoadingIndicator(
                            size = 24.dp,
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(28.dp))
                    }
                }

                val nextInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = player::next,
                    interactionSource = nextInteraction,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = nextInteraction)
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
                formattedRemaining,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
internal fun LyricsOptionsMenuDialog(
    onDismiss: () -> Unit,
    onOpenLyricsSource: (() -> Unit)?,
    canShareLyrics: Boolean,
    onShareLyrics: () -> Unit,
    lyricsFontScale: Float,
    onLyricsFontScaleChange: (Float) -> Unit,
    onLyricsFontScalePreview: (Float) -> Unit,
    displayMode: LyricsDisplayMode,
    translationAvailable: Boolean,
    onToggleTranslation: () -> Unit,
    onToggleRomanization: () -> Unit,
    lyricsOffsetMs: Long,
    onOpenLyricsOffset: (() -> Unit)?,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Lyrics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Text(
                    text = "Lyrics Options",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        if (onOpenLyricsSource != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        onOpenLyricsSource()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(Icons.Filled.Lyrics, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Text("Lyrics Provider", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        }

                        if (canShareLyrics) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        onShareLyrics()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Text("Share Lyrics", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        }

                        var localScale by remember(lyricsFontScale) { mutableFloatStateOf(lyricsFontScale) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Icon(Icons.Filled.FormatSize, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                    Text("Text Size: ${(localScale * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                }
                                if (localScale != 1.0f) {
                                    TextButton(
                                        onClick = {
                                            localScale = 1.0f
                                            onLyricsFontScalePreview(1.0f)
                                            onLyricsFontScaleChange(1.0f)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Text("Reset", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("A", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Slider(
                                    value = localScale,
                                    onValueChange = {
                                        localScale = it
                                        onLyricsFontScalePreview(it)
                                    },
                                    onValueChangeFinished = {
                                        onLyricsFontScaleChange(localScale)
                                    },
                                    valueRange = 0.7f..1.5f,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("A", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        if (translationAvailable) {
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onToggleTranslation)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(Icons.Filled.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Text("Translate Lyrics", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                androidx.compose.material3.Switch(
                                    checked = displayMode == LyricsDisplayMode.Translated,
                                    onCheckedChange = { onToggleTranslation() },
                                )
                            }

                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onToggleRomanization)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Text("Romanize Lyrics", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                androidx.compose.material3.Switch(
                                    checked = displayMode == LyricsDisplayMode.Romanized,
                                    onCheckedChange = { onToggleRomanization() },
                                )
                            }
                        }

                        if (onOpenLyricsOffset != null) {
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        onOpenLyricsOffset()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Text("Lyrics Sync Offset", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(
                                    "${lyricsOffsetMs}ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                androidx.compose.material3.FilledTonalButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text("Done", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

