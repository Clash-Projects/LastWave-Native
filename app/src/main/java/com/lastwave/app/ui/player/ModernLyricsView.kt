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
fun ModernLyricsPanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    progressState: StateFlow<PlaybackProgressState>? = null,
    wavySeekbarEnabled: Boolean = true,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    onRetry: () -> Unit = {},
    onOpenLyricsOffset: (() -> Unit)? = null,
    lyricsFontScale: Float = 1.0f,
    onLyricsFontScaleChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Manual sync correction (ms, + = lyrics earlier). Applies to lyric
     *  focus/highlight only â€” the seekbar below keeps true position. */
    lyricsOffsetMs: Long = 0L,
    controlsAutoHideSeconds: Int = 5,
    /** Controls auto-hide visibility state (hoisted or internally managed). */
    controlsVisibleState: Boolean? = null,
    onControlsVisibleChange: ((Boolean) -> Unit)? = null,
    /** Language the translate button targets; blank = follow the app language. */
    translationLanguage: String = "",
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    lyricsProviderStatuses: Map<LyricsProvider, com.lastwave.app.data.lyrics.LyricsSyncType> = emptyMap(),
    onProbeLyricsProviders: (() -> Unit)? = null,
    onSelectLyricsProvider: ((LyricsProvider) -> Unit)? = null,
    onTryAnotherLyricsSource: (() -> Unit)? = null,
    resolvedArtworkUrl: String? = null,
) {
    state.current ?: return

    var showShareDialog by rememberSaveable { mutableStateOf(false) }
    val canShareLyrics = lyricsState is LyricsUiState.Success &&
        !lyricsState.isInstrumental &&
        (lyricsState.lines.any { it.text.isNotBlank() } || !lyricsState.plainLyrics.isNullOrBlank())

    // High-frequency progress is collected ONLY in the leaf hosts below
    // (lyrics list + controls). Collecting it here would recompose the whole
    // panel incl. AnimatedContent + badge ~16x/sec. Other players isolate it.
    val progressFlow = progressState ?: player.progressState
    val progressInitial = PlaybackProgressState(positionMs = state.positionMs, durationMs = state.durationMs)
    var effectiveFontScale by remember(lyricsFontScale) { mutableFloatStateOf(lyricsFontScale) }

    // Floating buttons over the lyrics stay only while the user is active:
    // hide after the configured idle time and bring them back on the first tap.
    var internalControlsVisible by remember { mutableStateOf(true) }
    val controlsVisible = controlsVisibleState ?: internalControlsVisible
    val setControlsVisible: (Boolean) -> Unit = { visible ->
        internalControlsVisible = visible
        onControlsVisibleChange?.invoke(visible)
    }
    var fontSliderOpen by rememberSaveable { mutableStateOf(false) }
    var sourceDialogOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(controlsVisible, controlsAutoHideSeconds, fontSliderOpen, sourceDialogOpen, isFullscreen) {
        if (controlsVisible && controlsAutoHideSeconds > 0 && !fontSliderOpen && !sourceDialogOpen) {
            delay(controlsAutoHideSeconds * 1000L)
            setControlsVisible(false)
        }
    }
    // Entering or leaving lyrics fullscreen starts a fresh visible period.
    LaunchedEffect(isFullscreen) { setControlsVisible(true) }

    // On-demand lyric translation / romanization. Work is batched and cached
    // per track and language inside [LyricsTranslation]; the panel owns only
    // the shown mode, the loading flags and one shared reveal that grows all
    // sub-lines in and out together with the list.
    val translationContext = LocalContext.current
    val translationScope = rememberCoroutineScope()
    val translationTrackKey = state.current?.let { track ->
        track.videoId ?: "${track.artist}|${track.title}"
    }.orEmpty()
    val translatableLines = (lyricsState as? LyricsUiState.Success)
        ?.takeIf { it.isSynced && it.lines.isNotEmpty() && !it.isInstrumental }
        ?.lines

    var displayMode by remember { mutableStateOf(LyricsDisplayMode.Original) }
    var translationReady by remember { mutableStateOf<List<String?>?>(null) }
    var romanizationReady by remember { mutableStateOf<List<String?>?>(null) }
    var translationLoading by remember { mutableStateOf(false) }
    var romanizationLoading by remember { mutableStateOf(false) }
    var translationJob by remember { mutableStateOf<Job?>(null) }
    var romanizationJob by remember { mutableStateOf<Job?>(null) }
    var visibleSubLines by remember { mutableStateOf<List<String?>?>(null) }
    val subReveal = remember { Animatable(0f) }
    val targetLanguage = remember(translationLanguage) { translationWireTarget(translationLanguage) }

    // A new track, newly fetched lyrics or a different target language all
    // invalidate whatever was computed for the old ones.
    LaunchedEffect(translationTrackKey, translationLanguage, translatableLines) {
        translationJob?.cancel()
        romanizationJob?.cancel()
        translationJob = null
        romanizationJob = null
        translationLoading = false
        romanizationLoading = false
        translationReady = null
        displayMode = LyricsDisplayMode.Original
        visibleSubLines = null
        subReveal.snapTo(0f)
        val embedded = translatableLines?.takeIf { list ->
            list.any { !it.transliteration.isNullOrBlank() }
        }?.map { it.transliteration }
        romanizationReady = embedded
    }

    // A script switch closes the old sub-lines first, then grows the new ones
    // in, so a Romanized -> Translated flip reads as one motion instead of a
    // text swap.
    LaunchedEffect(displayMode, romanizationReady, translationReady) {
        if (displayMode == LyricsDisplayMode.Original) {
            if (subReveal.value > 0f || visibleSubLines != null) {
                subReveal.animateTo(0f, tween(LYRIC_SUBLINE_CLOSE_MS, easing = FastOutSlowInEasing))
                visibleSubLines = null
            }
        } else {
            val targetSubLines = when (displayMode) {
                LyricsDisplayMode.Romanized -> romanizationReady ?: translatableLines?.map { it.transliteration }
                LyricsDisplayMode.Translated -> translationReady
                LyricsDisplayMode.Original -> null
            }
            if (targetSubLines != null) {
                visibleSubLines = targetSubLines
                subReveal.animateTo(1f, tween(LYRIC_SUBLINE_OPEN_MS, easing = FastOutSlowInEasing))
            }
        }
    }

    fun showLyricsToast(message: String) {
        android.widget.Toast.makeText(translationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun cancelTranslationLoad() {
        translationJob?.cancel()
        translationJob = null
        translationLoading = false
    }

    fun cancelRomanizationLoad() {
        romanizationJob?.cancel()
        romanizationJob = null
        romanizationLoading = false
    }

    val onToggleTranslation: () -> Unit = {
        val lines = translatableLines
        when {
            lines == null -> Unit
            displayMode == LyricsDisplayMode.Translated -> {
                // Turning the mode off abandons the other mode's in-flight
                // load too: the user has moved on from both scripts.
                cancelRomanizationLoad()
                displayMode = LyricsDisplayMode.Original
            }
            translationReady != null -> {
                cancelRomanizationLoad()
                displayMode = LyricsDisplayMode.Translated
            }
            !translationLoading -> {
                cancelRomanizationLoad()
                translationLoading = true
                translationJob = translationScope.launch {
                    try {
                        when (val result = LyricsTranslation.translate(translationTrackKey, lines, targetLanguage)) {
                            is LyricsTranslation.Result.Translated -> {
                                translationReady = result.subLines
                                displayMode = LyricsDisplayMode.Translated
                            }
                            is LyricsTranslation.Result.SameLanguage -> showLyricsToast(
                                translationContext.getString(
                                    R.string.ly_toast_already_language,
                                    translationLanguageName(result.language, Locale.getDefault()),
                                ),
                            )
                            LyricsTranslation.Result.Unavailable -> showLyricsToast(
                                translationContext.getString(R.string.ly_toast_translation_unavailable),
                            )
                        }
                    } finally {
                        if (translationJob === coroutineContext[Job]) {
                            translationLoading = false
                            translationJob = null
                        }
                    }
                }
            }
        }
    }

    val onToggleRomanization: () -> Unit = {
        val lines = translatableLines
        when {
            lines == null -> Unit
            displayMode == LyricsDisplayMode.Romanized -> {
                cancelTranslationLoad()
                displayMode = LyricsDisplayMode.Original
            }
            romanizationReady != null -> {
                cancelTranslationLoad()
                displayMode = LyricsDisplayMode.Romanized
            }
            !romanizationLoading -> {
                cancelTranslationLoad()
                romanizationLoading = true
                romanizationJob = translationScope.launch {
                    try {
                        when (val result = LyricsTranslation.romanize(translationTrackKey, lines)) {
                            is LyricsTranslation.RomanizationResult.Romanized -> {
                                romanizationReady = result.subLines
                                displayMode = LyricsDisplayMode.Romanized
                            }
                            LyricsTranslation.RomanizationResult.AlreadyRomanized -> showLyricsToast(
                                translationContext.getString(R.string.ly_toast_already_romanized),
                            )
                            LyricsTranslation.RomanizationResult.Unavailable -> showLyricsToast(
                                translationContext.getString(R.string.ly_toast_romanization_unavailable),
                            )
                        }
                    } finally {
                        if (romanizationJob === coroutineContext[Job]) {
                            romanizationLoading = false
                            romanizationJob = null
                        }
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = lyricsState,
                transitionSpec = {
                    (fadeIn(tween(ExpressiveMotion.Quick)) +
                        androidx.compose.animation.scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(ExpressiveMotion.Quick)) +
                            androidx.compose.animation.scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.96f))
                },
                label = "modernLyricsStateContent",
                modifier = Modifier.fillMaxSize(),
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
                                    color = Color.White,
                                )
                                Text(
                                    "Finding lyrics...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.70f),
                                )
                            }
                        }
                    }

                    is LyricsUiState.Empty, is LyricsUiState.Error -> {
                        ModernEmptyLyricsView(
                            isInstrumental = false,
                            onRetry = onRetry,
                        )
                    }

                    is LyricsUiState.Success -> {
                        if (targetState.isInstrumental) {
                            ModernEmptyLyricsView(
                                isInstrumental = true,
                                onRetry = onRetry,
                            )
                        } else if (targetState.isSynced && targetState.lines.isNotEmpty()) {
                            // Reference parity: one provider timestamp stays one
                            // drawn row â€” long rows wrap visually via Text
                            // soft-wrap on their own timestamp, never sliced
                            // into fabricated timed chunks.
                            val isWordSynced = targetState.isWordSynced ||
                                remember(targetState.lines) { targetState.lines.any { it.hasSyllables } }
                            val isOverallRtl = remember(targetState.lines) {
                                val meaningful = targetState.lines.filter { it.text.isNotBlank() && it.text != "â™ª" }
                                if (meaningful.isEmpty()) false
                                else meaningful.count { it.isRtl } > meaningful.size / 2
                            }
                            // Apple Music word-sync rows run full-sentence wide, so
                            // they keep a compact size while other providers use
                            // the standard tier. Sizes stay moderate so wrapped
                            // rows clear the edges at the focus zoom.
                            val isAppleMusic = remember(targetState.source) {
                                targetState.source?.contains("Apple Music", ignoreCase = true) == true
                            }
                            // Shared style instances drawn by every row below.
                            val currentTextStyle = LocalTextStyle.current
                            // App font on: dedicated heavy lyric cuts of the
                            // variable font. App font off: the system font,
                            // whose Bold is a real weight.
                            val useAppFont = MaterialTheme.typography.bodyLarge.fontFamily != androidx.compose.ui.text.font.FontFamily.Default
                            val karaokeNormalStyle = remember(currentTextStyle, isAppleMusic, isWordSynced, effectiveFontScale, useAppFont) {
                                val baseSize = (if (isAppleMusic) 30f else if (isWordSynced) 34f else 32f) * effectiveFontScale
                                currentTextStyle.copy(
                                    fontFamily = if (useAppFont) com.lastwave.app.ui.theme.LyricsLeadFontFamily else androidx.compose.ui.text.font.FontFamily.Default,
                                    fontSize = baseSize.sp,
                                    lineHeight = (baseSize * 1.20f).sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.6).sp,
                                    textMotion = TextMotion.Animated,
                                )
                            }
                            val karaokeAccompanimentStyle = remember(currentTextStyle, isAppleMusic, isWordSynced, effectiveFontScale, useAppFont) {
                                // Backing vocals: 0.65x of the lead row.
                                val baseSize = (if (isAppleMusic) 30f else if (isWordSynced) 34f else 32f) * 0.65f * effectiveFontScale
                                currentTextStyle.copy(
                                    fontFamily = if (useAppFont) com.lastwave.app.ui.theme.LyricsBackingFontFamily else androidx.compose.ui.text.font.FontFamily.Default,
                                    fontSize = baseSize.sp,
                                    lineHeight = (baseSize * 1.22f).sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.3).sp,
                                    textMotion = TextMotion.Animated,
                                )
                            }
                            // Sub-lines (translation / romanization) draw
                            // smaller and lighter than the lyric they follow.
                            val subLineStyle = remember(currentTextStyle, effectiveFontScale, useAppFont) {
                                currentTextStyle.copy(
                                    fontFamily = if (useAppFont) com.lastwave.app.ui.theme.LyricsSubLineFontFamily else androidx.compose.ui.text.font.FontFamily.Default,
                                    fontSize = (16f * effectiveFontScale).sp,
                                    lineHeight = (22f * effectiveFontScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (-0.1).sp,
                                    textMotion = TextMotion.Animated,
                                )
                            }

                            val layoutDirection = if (isOverallRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                            // Sync-type badge: makes it visible why words animate
                            // (word-sync) or just scroll (line-sync). No source
                            // or provider name is ever shown on the canvas.
                            val wordSyncLabel = stringResource(R.string.ly_word_sync)
                            val lineSyncLabel = stringResource(R.string.ly_line_sync)
                            val syncLabel = if (isWordSynced) wordSyncLabel else lineSyncLabel
                            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = 8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = liquidGlassContainerColor(Color.White.copy(alpha = 0.16f)),
                                            contentColor = Color.White.copy(alpha = 0.92f),
                                        ) {
                                            Text(
                                                text = syncLabel,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    letterSpacing = 0.6.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                            )
                                        }
                                    }
                                    SyncedLyricsProgressHost(
                                        lines = targetState.lines,
                                        isOverallRtl = isOverallRtl,
                                        normalStyle = karaokeNormalStyle,
                                        accompanimentStyle = karaokeAccompanimentStyle,
                                        subLineStyle = subLineStyle,
                                        subLines = visibleSubLines,
                                        subReveal = { subReveal.value },
                                        displayMode = displayMode,
                                        progressFlow = progressFlow,
                                        progressInitial = progressInitial,
                                        state = state,
                                        player = player,
                                        lyricsOffsetMs = lyricsOffsetMs,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                    )
                                }
                            }
                        } else if (!targetState.plainLyrics.isNullOrBlank()) {
                            ModernPlainLyricsView(
                                plainLyrics = targetState.plainLyrics,
                                lyricsFontScale = effectiveFontScale,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ModernEmptyLyricsView(
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
            // Any tap while the buttons are hidden brings them back â€” and
            // only that: it must not also seek to the lyric line underneath.
            if (!controlsVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { setControlsVisible(true) } },
                )
            }
        }

        ModernLyricsControlsHost(
            state = state,
            progressFlow = progressFlow,
            progressInitial = progressInitial,
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
            translationAvailable = translatableLines != null,
            onToggleTranslation = onToggleTranslation,
            onToggleRomanization = onToggleRomanization,
            showFontSlider = fontSliderOpen,
            onShowFontSliderChange = { fontSliderOpen = it },
            onOpenLyricsSource = if (onSelectLyricsProvider != null || onTryAnotherLyricsSource != null) {
                { sourceDialogOpen = true }
            } else null,
            lyricsFontScale = effectiveFontScale,
            onLyricsFontScaleChange = { newScale ->
                effectiveFontScale = newScale
                onLyricsFontScaleChange(newScale)
            },
            onLyricsFontScalePreview = { previewScale ->
                effectiveFontScale = previewScale
            },
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor,
            canShareLyrics = canShareLyrics,
            onShareLyrics = { showShareDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp, top = 6.dp),
        )

        if (sourceDialogOpen) {
            LaunchedEffect(Unit) {
                onProbeLyricsProviders?.invoke()
            }
            val currentSource = (lyricsState as? LyricsUiState.Success)?.source
            val currentProvider = remember(currentSource) { providerForSource(currentSource) }
            LyricsSourceDialog(
                currentSource = currentSource,
                currentProvider = currentProvider,
                providerStatuses = lyricsProviderStatuses,
                onSelectProvider = { provider ->
                    sourceDialogOpen = false
                    onSelectLyricsProvider?.invoke(provider)
                },
                onTryAnotherSource = {
                    sourceDialogOpen = false
                    onTryAnotherLyricsSource?.invoke()
                },
                onDismiss = { sourceDialogOpen = false },
            )
        }
    }

    if (showShareDialog) {
        val currentTrack = state.current
        if (currentTrack != null) {
            val trackInfo = remember(currentTrack, resolvedArtworkUrl) {
                TrackInfo(
                    title = currentTrack.title,
                    artist = currentTrack.artist,
                    artworkUrl = currentTrack.artworkUrl,
                    resolvedArtworkUrl = resolvedArtworkUrl,
                )
            }
            val activeIndex = remember(lyricsState, lyricsOffsetMs) {
                val lines = (lyricsState as? LyricsUiState.Success)?.lines.orEmpty()
                if (lines.isNotEmpty()) {
                    activeLineIndex(lines, progressFlow.value.positionMs + lyricsOffsetMs)
                } else {
                    -1
                }
            }
            val snapshot = remember(lyricsState, trackInfo) {
                buildLyricsSnapshot(lyricsState, trackInfo, activeIndex)
            }
            if (snapshot != null) {
                ShareLyricsDialog(
                    snapshot = snapshot,
                    onDismiss = { showShareDialog = false },
                    primaryColor = primaryColor,
                )
            }
        }
    }
}
