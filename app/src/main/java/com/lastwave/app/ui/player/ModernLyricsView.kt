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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lyrics
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
import java.util.Locale

/** Which secondary script the floating buttons currently draw under each
 *  line: none ([Original]), the pronunciation ([Romanized]) or a translation
 *  ([Translated]). At most one mode is shown at a time. */
private enum class LyricsDisplayMode { Original, Romanized, Translated }

/** Line falloff by distance from the active row: alpha and soften radius.
 *  Past the end of the array the last value holds, so distance never needs a
 *  branch. The active line's own scale (1.0) lives with the row code. */
private val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.80f, 0.70f, 0.58f, 0.46f)
private val LINE_FALLOFF_BLUR_DP = floatArrayOf(0f, 1f, 1f, 1.7f, 2.4f)

/** Glide curve for the follow scroll and row scale: quick departure, long
 *  soft landing — closer to a platform sheet than to a linear scroll. */
private val LYRIC_EASE = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)

/** Follow-scroll duration: small corrections stay crisp, whole-verse jumps
 *  glide. Gap (px between the row and the focus point) picks the point inside
 *  the band; the band itself is what keeps motion calm. */
private const val LYRIC_SCROLL_MIN_MS = 350f
private const val LYRIC_SCROLL_MAX_MS = 500f

/** Sub-line reveal: slightly unhurried in, clipped and quick out. */
private const val LYRIC_SUBLINE_OPEN_MS = 460
private const val LYRIC_SUBLINE_CLOSE_MS = 260

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
     *  focus/highlight only — the seekbar below keeps true position. */
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
        romanizationReady = null
        displayMode = LyricsDisplayMode.Original
        visibleSubLines = null
        subReveal.snapTo(0f)
    }

    // A script switch closes the old sub-lines first, then grows the new ones
    // in, so a Romanized -> Translated flip reads as one motion instead of a
    // text swap.
    LaunchedEffect(displayMode) {
        if (displayMode == LyricsDisplayMode.Original) {
            if (subReveal.value > 0f || visibleSubLines != null) {
                subReveal.animateTo(0f, tween(LYRIC_SUBLINE_CLOSE_MS, easing = FastOutSlowInEasing))
                visibleSubLines = null
            }
        } else {
            if (subReveal.value > 0f) {
                subReveal.animateTo(0f, tween(LYRIC_SUBLINE_CLOSE_MS, easing = FastOutSlowInEasing))
            }
            visibleSubLines = when (displayMode) {
                LyricsDisplayMode.Romanized -> romanizationReady
                LyricsDisplayMode.Translated -> translationReady
                LyricsDisplayMode.Original -> null
            }
            subReveal.animateTo(1f, tween(LYRIC_SUBLINE_OPEN_MS, easing = FastOutSlowInEasing))
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
                                    "Finding lyrics…",
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
                            // drawn row — long rows wrap visually via Text
                            // soft-wrap on their own timestamp, never sliced
                            // into fabricated timed chunks.
                            val isWordSynced = targetState.isWordSynced ||
                                remember(targetState.lines) { targetState.lines.any { it.hasSyllables } }
                            val isOverallRtl = remember(targetState.lines) {
                                val meaningful = targetState.lines.filter { it.text.isNotBlank() && it.text != "♪" }
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
                            val karaokeNormalStyle = remember(currentTextStyle, isAppleMusic, isWordSynced, effectiveFontScale) {
                                val baseSize = (if (isAppleMusic) 28f else if (isWordSynced) 32f else 30f) * effectiveFontScale
                                currentTextStyle.copy(
                                    fontSize = baseSize.sp,
                                    lineHeight = (baseSize * 1.38f).sp,
                                    fontWeight = FontWeight.Bold,
                                    textMotion = TextMotion.Animated,
                                )
                            }
                            val karaokeAccompanimentStyle = remember(currentTextStyle, isAppleMusic, isWordSynced, effectiveFontScale) {
                                val baseSize = (if (isAppleMusic) 22f else if (isWordSynced) 24f else 22f) * effectiveFontScale
                                currentTextStyle.copy(
                                    fontSize = baseSize.sp,
                                    lineHeight = (baseSize * 1.35f).sp,
                                    fontWeight = FontWeight.Bold,
                                    textMotion = TextMotion.Animated,
                                )
                            }
                            // Sub-lines (translation / romanization) draw
                            // smaller and lighter than the lyric they follow.
                            val subLineStyle = remember(currentTextStyle, effectiveFontScale) {
                                currentTextStyle.copy(
                                    fontSize = (18f * effectiveFontScale).sp,
                                    lineHeight = (25f * effectiveFontScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.2.sp,
                                    textMotion = TextMotion.Animated,
                                )
                            }

                            val layoutDirection = if (isOverallRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                            // Short provider badge: makes it visible why words
                            // animate (word-sync) or just scroll (line-sync).
                            val wordSyncLabel = stringResource(R.string.ly_word_sync)
                            val lineSyncLabel = stringResource(R.string.ly_line_sync)
                            val providerFallback = stringResource(R.string.ly_provider_fallback)
                            val syncLabel = remember(targetState.source, isWordSynced) {
                                val provider = targetState.source
                                    ?.substringBefore(" (")
                                    ?.takeIf { it.isNotBlank() } ?: providerFallback
                                "${if (isWordSynced) wordSyncLabel else lineSyncLabel} • $provider"
                            }
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
            // Any tap while the buttons are hidden brings them back — and
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
            val currentSource = (lyricsState as? LyricsUiState.Success)?.source
            val currentProvider = remember(currentSource) { providerForSource(currentSource) }
            LyricsSourceDialog(
                currentSource = currentSource,
                currentProvider = currentProvider,
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

/**
 * Smoothly melts the top and bottom edges of the lyrics viewport using a hardware-accelerated
 * offscreen alpha gradient mask (BlendMode.DstIn). Eliminates abrupt hard-edge clipping as
 * lyrics scroll down toward the bottom player controls.
 */
private fun Modifier.lyricsFadingEdge(
    topFadeDp: androidx.compose.ui.unit.Dp = 36.dp,
    bottomFadeDp: androidx.compose.ui.unit.Dp = 110.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val topFadePx = topFadeDp.toPx()
        val bottomFadePx = bottomFadeDp.toPx()
        val height = size.height
        if (height > topFadePx + bottomFadePx) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    (topFadePx / height) to Color.Black,
                    ((height - bottomFadePx) / height) to Color.Black,
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Reference lyrics sync list (Metrolist/SimpMusic parity, no gradle canvas).
 *
 * Sync contract, identical for word-by-word and line-by-line rows:
 * - One provider timestamp = one drawn row. Rows are never merged, split,
 *   or re-timed here; [LyricLine.timeMs] is the single source of truth.
 * - The active row is the last row with `timeMs <= effectivePosition`
 *   (binary search, same as the classic view and the reference clients'
 *   `findCurrentLineIndex`). A row's end is implied by the next row's start,
 *   never stored, so overlapping focus windows are impossible by
 *   construction (the old `maxOf(naturalEnd, nextStart)` mapping kept two
 *   rows lit around every boundary).
 * - Word fill reads each syllable's own `[timeMs, timeMs + durationMs)`
 *   window (already de-overlapped upstream by `normalizeLyricTiming`). A
 *   zero-duration syllable holds until the next syllable starts, matching
 *   the reference `findActiveLineIndices` end-time fallback.
 * - Position is the raw playhead plus the manual offset on every tick — no
 *   time quantization. Reference clients sample per frame (8ms /
 *   withFrameNanos); the 60ms progress ticker feeding this list is already
 *   the coarsest step word fills can tolerate (the old 120ms bucketing
 *   visibly stalled syllables 100-200ms long).
 * - Long rows wrap visually via [Text] soft-wrap on their own timestamp.
 *   They are never sliced into fabricated timed chunks: the old
 *   proportional-width slicing invented timestamps the vocals never sang,
 *   which is why line-by-line drifted and word-by-word stalled.
 */
@Composable
private fun ModernSyncedLyricsList(
    lines: List<LyricLine>,
    isOverallRtl: Boolean,
    normalStyle: TextStyle,
    accompanimentStyle: TextStyle,
    currentPositionMs: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    lyricsOffsetMs: Long = 0L,
    /** Aligned sub-line per row index ([LyricsTranslation] output), or null. */
    subLines: List<String?>? = null,
    /** Shared 0..1 reveal for every sub-line; read per frame, not recomposed. */
    subReveal: () -> Float = { 0f },
    subLineStyle: TextStyle? = null,
) {
    val listState = rememberLazyListState()
    var userScrolledTime by remember { mutableLongStateOf(0L) }
    val currentPositionMsState by rememberUpdatedState(currentPositionMs)

    val activeIndex by remember(lines) {
        derivedStateOf { activeLineIndex(lines, currentPositionMsState()) }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    if (isDragged) {
        userScrolledTime = System.currentTimeMillis()
    }

    LaunchedEffect(activeIndex, isPlaying) {
        // Auto-follow yields to the user for a short grace period after a
        // manual scroll; a paused player has no urgency, so the grace is
        // longer and the line only takes the focus back once the user is
        // clearly done browsing.
        val resumeDelayMs = if (isPlaying) 600L else 5000L
        val timeSinceUserScroll = System.currentTimeMillis() - userScrolledTime
        if (timeSinceUserScroll > resumeDelayMs && activeIndex in lines.indices) {
            runCatching {
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                val targetItem = layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                if (targetItem != null && viewportHeight > 0) {
                    val targetY = viewportHeight / 3
                    val delta = targetItem.offset - targetY
                    listState.animateScrollBy(delta.toFloat(), lyricScrollDurationMs(delta.toFloat()))
                } else {
                    val targetIndex = (activeIndex - 1).coerceAtLeast(0)
                    listState.animateScrollToItem(index = targetIndex, scrollOffset = 0)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.lyricsFadingEdge(topFadeDp = 36.dp, bottomFadeDp = 110.dp),
        contentPadding = PaddingValues(top = 40.dp, bottom = 130.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
            val isActive = index == activeIndex
            val isPast = activeIndex >= 0 && index < activeIndex
            val distance = kotlin.math.abs(index - activeIndex)
            val isBgRow = line.syllables.isNotEmpty() && line.syllables.all { it.isBackground }
            val isLineRtl = remember(line, isOverallRtl) {
                line.isRtl || (isOverallRtl && (line.text.isBlank() || line.text == "♪"))
            }

            // Focus is carried by colour and light, not size: the active row
            // barely grows and neighbours stay close to full size, so a
            // line-to-line move reads as the spotlight travelling instead of
            // every row breathing in and out.
            val scaleTarget = if (isActive) 1f else 0.98f
            val scale by animateFloatAsState(
                targetValue = scaleTarget,
                animationSpec = tween(400, easing = LYRIC_EASE),
                label = "modernLyricScale_$index",
            )
            val alphaTarget = LINE_FALLOFF_ALPHA[distance.coerceAtMost(LINE_FALLOFF_ALPHA.lastIndex)]
            val alpha by animateFloatAsState(
                targetValue = alphaTarget,
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                label = "modernLyricAlpha_$index",
            )
            // Depth of field: lines away from the sung one soften, but snap
            // back to crisp while the user is browsing.
            val blurTarget = if (isActive || isDragged || activeIndex < 0) 0f
            else LINE_FALLOFF_BLUR_DP[distance.coerceAtMost(LINE_FALLOFF_BLUR_DP.lastIndex)]
            val blurRadius by animateFloatAsState(
                targetValue = blurTarget,
                animationSpec = tween(380, easing = FastOutSlowInEasing),
                label = "modernLyricBlur_$index",
            )
            // Elastic trail: when the active line advances, the lines below
            // lag behind the scroll and spring into place one after another.
            val trail = remember { Animatable(0f) }
            LaunchedEffect(activeIndex) {
                val rel = index - activeIndex
                if (isPlaying && !isDragged && activeIndex > 0 && rel in 0..6) {
                    trail.snapTo(7f + rel * 3f)
                    delay(rel * 38L)
                    trail.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 180f))
                } else if (trail.value != 0f) {
                    trail.snapTo(0f)
                }
            }

            val onsetProgress = remember { Animatable(0f) }
            LaunchedEffect(isActive, isPlaying) {
                if (isActive && isPlaying) {
                    onsetProgress.snapTo(0f)
                    onsetProgress.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
                } else {
                    onsetProgress.snapTo(0f)
                }
            }
            val onsetWave = if (isActive && isPlaying && onsetProgress.value in 0.001f..0.999f) {
                kotlin.math.sin(Math.PI.toFloat() * onsetProgress.value)
            } else 0f
            val totalScale = scale * (1f + 0.020f * onsetWave)

            val translationYTarget = if (isActive) -3.5f else if (isPast) -1.5f else 3.5f
            val translationY by animateFloatAsState(
                targetValue = translationYTarget,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                label = "modernLyricTransY_$index",
            )

            val lineLayoutDirection = if (isLineRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(if (isLineRtl) 1f else 0f, 0.5f)
                            scaleX = totalScale
                            scaleY = totalScale
                            this.translationY = (translationY + trail.value) * density
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                val r = blurRadius * density
                                renderEffect = if (r > 0.3f) BlurEffect(r, r) else null
                            }
                            this.alpha = alpha * if (isBgRow) (if (isActive) 0.85f else 0.55f) else 1f
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onSeek(line.timeMs)
                        }
                        .padding(horizontal = 12.dp, vertical = if (isActive) 8.dp else 6.dp),
                ) {
                    val fontStyle = if (isBgRow) accompanimentStyle else normalStyle
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ModernWordByWordLine(
                            line = line,
                            currentPositionMs = currentPositionMs,
                            isActive = isActive,
                            activeColor = Color.White,
                            inactiveColor = Color.White.copy(alpha = 0.55f),
                            fontStyle = fontStyle,
                            isRtl = isLineRtl,
                            modifier = Modifier.padding(start = if (isBgRow) 14.dp else 0.dp),
                        )
                        // Translation / romanization copy under the lyric.
                        // The engine returns null for lines that have no
                        // distinct sub-line, so no per-row text comparison
                        // is needed here.
                        val subLine = subLines?.getOrNull(index)
                        if (subLine != null && subLineStyle != null) {
                            LyricSubLine(
                                text = subLine,
                                style = subLineStyle,
                                reveal = subReveal,
                            )
                        }
                    }
                }
            }
        }
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
private fun LyricSubLine(
    text: String,
    style: TextStyle,
    reveal: () -> Float,
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
        Text(
            text = text,
            style = style,
            color = Color.White.copy(alpha = 0.78f),
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .graphicsLayer {
                    val open = reveal()
                    alpha = open * open
                    translationY = -size.height * (1f - open) * 0.6f
                },
        )
    }
}

@Composable
private fun ModernWordByWordLine(
    line: LyricLine,
    currentPositionMs: () -> Long,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    fontStyle: TextStyle,
    isRtl: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val lineLayoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
        val activeShadow = remember {
            Shadow(
                color = Color.White.copy(alpha = 0.50f),
                blurRadius = 22f,
                offset = Offset.Zero,
            )
        }
        val activeStyle = remember(fontStyle, isActive) {
            // On S+ the glow comes from a blurred copy of the same Text
            // (see LyricGlowText); the shadow stays only as the pre-S
            // fallback where Modifier.blur is a no-op.
            if (isActive && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                fontStyle.copy(shadow = activeShadow)
            } else {
                fontStyle
            }
        }

        if (!line.hasSyllables || !isActive) {
            val animatedColor by animateColorAsState(
                targetValue = if (isActive) activeColor else inactiveColor.copy(alpha = 0.44f),
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                label = "lineColor",
            )
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (isActive) {
                        LyricGlowText(text = line.text.ifBlank { "♪" }, style = fontStyle)
                    }
                    Text(
                        text = line.text.ifBlank { "♪" },
                        style = activeStyle,
                        color = animatedColor,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!line.transliteration.isNullOrBlank() && isActive) {
                    Text(
                        text = line.transliteration,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
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
            return@CompositionLocalProvider
        }

        val displayTexts = remember(line) { resolveSyllableDisplayTexts(line) }
        val fullText = remember(line, displayTexts) {
            line.syllables.indices.joinToString("") { displayTexts.getOrElse(it) { line.syllables[it].text } }
        }
        // Char range of each syllable inside [fullText].
        val charRanges = remember(line, displayTexts) {
            var offset = 0
            line.syllables.indices.map { i ->
                val start = offset
                offset += displayTexts.getOrElse(i) { line.syllables[i].text }.length
                start to offset
            }
        }
        var layout by remember(fullText) { mutableStateOf<TextLayoutResult?>(null) }
        val maskPaint = remember {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            }
        }
        val clearPaint = remember {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            }
        }

        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            // Karaoke fill: dim base text + bright copy clipped to the sung
            // extent with a smooth feathered gradient wipe.
            Box(modifier = Modifier.fillMaxWidth()) {
                if (isActive) {
                    LyricGlowText(text = fullText, style = fontStyle)
                }
                Text(
                    text = fullText,
                    style = fontStyle,
                    color = inactiveColor.copy(alpha = 0.40f),
                    textAlign = TextAlign.Start,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            val l = layout
                            if (l == null) drawContent()
                            else drawWithSyllableLift(l, line, charRanges, currentPositionMs(), 3.dp.toPx(), isRtl)
                        },
                )
                Text(
                    text = fullText,
                    style = activeStyle,
                    color = activeColor,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            val l = layout ?: return@drawWithContent
                            val textLength = fullText.length
                            if (textLength == 0) return@drawWithContent
                            val pos = currentPositionMs()
                            var fill = 0f
                            var sylIdx = -1
                            var sylP = 0f
                            for (i in line.syllables.indices) {
                                val syl = line.syllables[i]
                                if (pos < syl.timeMs) break
                                val (cs, ce) = charRanges[i]
                                val linear = if (syl.durationMs <= 0L) 1f
                                else ((pos - syl.timeMs).toFloat() / syl.durationMs).coerceIn(0f, 1f)
                                // Mild ease-out: a sung syllable attacks fast and
                                // sustains, so a linear wipe reads as lagging.
                                val p = 1f - Math.pow((1f - linear).toDouble(), 1.4).toFloat()
                                fill = cs + (ce - cs) * p
                                sylIdx = i
                                sylP = p
                                if (p < 1f) break
                            }
                            if (fill <= 0f) return@drawWithContent
                            val liftPx = 3.dp.toPx()
                            if (fill >= textLength) {
                                drawWithSyllableLift(l, line, charRanges, pos, liftPx, isRtl)
                                return@drawWithContent
                            }
                            // Interpolate across the syllable's pixel extent, not
                            // per char: in Indic/complex scripts offsets inside a
                            // grapheme cluster (matras, conjuncts) report stalled
                            // or backward x, which froze/jumped the wipe.
                            val (sCs, sCe) = charRanges[sylIdx]
                            val startIdx = sCs.coerceIn(0, textLength - 1)
                            val lastIdx = (sCe - 1).coerceIn(startIdx, textLength - 1)
                            val sameLine = l.getLineForOffset(startIdx) == l.getLineForOffset(lastIdx)
                            val lineIdx: Int
                            val x: Float
                            var feather = 12f
                            if (sameLine) {
                                lineIdx = l.getLineForOffset(startIdx)
                                val xs = l.getHorizontalPosition(startIdx, true)
                                val xe = if (sCe < textLength && l.getLineForOffset(sCe) == lineIdx) {
                                    l.getHorizontalPosition(sCe, true)
                                } else if (isRtl) l.getLineLeft(lineIdx) else l.getLineRight(lineIdx)
                                x = xs + (xe - xs) * sylP
                                // Soft edge proportional to the syllable: short
                                // syllables stay crisp, long held notes glide.
                                feather = (kotlin.math.abs(xe - xs) * 0.3f).coerceIn(5f, 18f)
                            } else {
                                val charIdx = fill.toInt().coerceIn(0, textLength - 1)
                                val frac = fill - charIdx
                                lineIdx = l.getLineForOffset(charIdx)
                                val x0 = l.getHorizontalPosition(charIdx, true)
                                val x1 = if (charIdx + 1 < textLength && l.getLineForOffset(charIdx + 1) == lineIdx) {
                                    l.getHorizontalPosition(charIdx + 1, true)
                                } else if (isRtl) l.getLineLeft(lineIdx) else l.getLineRight(lineIdx)
                                x = x0 + (x1 - x0) * frac
                            }
                            // Lifted glyphs rise above the row; widen the mask so
                            // the unsung part of a rising syllable stays masked.
                            val top = l.getLineTop(lineIdx) - liftPx * 1.5f
                            val bottom = l.getLineBottom(lineIdx)

                            val canvas = drawContext.canvas.nativeCanvas
                            val checkpoint = canvas.saveLayer(0f, 0f, size.width, size.height, null)
                            drawWithSyllableLift(l, line, charRanges, pos, liftPx, isRtl)

                            if (isRtl) {
                                val xStart = (x - feather).coerceAtLeast(0f)
                                val xEnd = (x + feather).coerceAtMost(size.width)
                                if (xStart > 0f) {
                                    canvas.drawRect(0f, top, xStart, bottom, clearPaint)
                                }
                                if (xEnd > xStart) {
                                    maskPaint.shader = android.graphics.LinearGradient(
                                        xStart, 0f, xEnd, 0f,
                                        android.graphics.Color.TRANSPARENT,
                                        android.graphics.Color.WHITE,
                                        android.graphics.Shader.TileMode.CLAMP,
                                    )
                                    canvas.drawRect(xStart, top, xEnd, bottom, maskPaint)
                                }
                            } else {
                                val xStart = (x - feather).coerceAtLeast(0f)
                                val xEnd = (x + feather).coerceAtMost(size.width)
                                if (xEnd < size.width) {
                                    canvas.drawRect(xEnd, top, size.width, bottom, clearPaint)
                                }
                                if (xEnd > xStart) {
                                    maskPaint.shader = android.graphics.LinearGradient(
                                        xStart, 0f, xEnd, 0f,
                                        android.graphics.Color.WHITE,
                                        android.graphics.Color.TRANSPARENT,
                                        android.graphics.Shader.TileMode.CLAMP,
                                    )
                                    canvas.drawRect(xStart, top, xEnd, bottom, maskPaint)
                                }
                            }
                            if (bottom < size.height) {
                                canvas.drawRect(0f, bottom, size.width, size.height, clearPaint)
                            }
                            canvas.restoreToCount(checkpoint)
                        },
                )
            }

            if (!line.transliteration.isNullOrBlank()) {
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

/** Radius/alpha of the active line's glow, kept in one place. */
private val LYRIC_GLOW_RADIUS = 8.dp
private const val LYRIC_GLOW_ALPHA = 0.5f

/**
 * The active line's glow: a blurred copy of the very Text drawn on top of it.
 *
 * A TextStyle.shadow is positioned by the platform's per-glyph shadow pass,
 * which drifts off complex-script shaping — on Gurmukhi the halo lands offset
 * from the glyphs and reads as a second, wrong image behind the line. A
 * layout-identical duplicate (same text, style, direction and width) has the
 * same bounds by construction, for every script.
 *
 * Below API 31 Modifier.blur is a no-op, so the caller keeps the shadow there
 * and this draws nothing. An unbounded edge treatment keeps the halo from
 * being cut off at the Text's own bounds.
 */
@Composable
private fun LyricGlowText(text: String, style: TextStyle) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
    Text(
        text = text,
        style = style,
        color = Color.White.copy(alpha = LYRIC_GLOW_ALPHA),
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .blur(LYRIC_GLOW_RADIUS, BlurredEdgeTreatment.Unbounded),
    )
}

@Composable
private fun ModernPlainLyricsView(
    plainLyrics: String,
    lyricsFontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val isRtl = remember(plainLyrics) { isRtlText(plainLyrics) }
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier
                .lyricsFadingEdge(topFadeDp = 24.dp, bottomFadeDp = 100.dp)
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
                    stringResource(R.string.ly_not_synced),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.70f),
                )
            }

            Text(
                text = plainLyrics,
                style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = (28f * lyricsFontScale).sp,
lineHeight = (46f * lyricsFontScale).sp,
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
private fun ModernEmptyLyricsView(
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
                text = if (isInstrumental) stringResource(R.string.ly_instrumental) else stringResource(R.string.ly_none),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            Text(
                text = if (isInstrumental) {
                    stringResource(R.string.ly_no_vocals)
                } else {
                    stringResource(R.string.ly_no_synced)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )

            if (!isInstrumental) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ly_retry))
                }
            }
        }
    }
}

/**
 * Leaf host: collects progress ONLY for the lyrics list, so badge +
 * AnimatedContent above never recompose on ticks. Reference-client parity
 * (Metrolist/SimpMusic): the raw playhead position drives focus every tick
 * with no time quantization — word fills step at syllable granularity
 * (>=50ms), so throttling would visibly stall the karaoke highlight.
 */
@Composable
private fun SyncedLyricsProgressHost(
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
    modifier: Modifier = Modifier,
) {
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = progressInitial)
    val latestSampleMs by rememberUpdatedState(progress.positionMs)
    val offsetState by rememberUpdatedState(lyricsOffsetMs)
    val framePositionMs = remember { mutableLongStateOf(progress.positionMs) }
    // Vsync clock (90/120Hz): advances by real frame time and is steered
    // toward the ExoPlayer playhead (sampled every ~60ms). Never runs
    // backwards on normal drift; snaps instantly on seeks/track changes.
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) {
            snapshotFlow { latestSampleMs }.collect { framePositionMs.longValue = it }
            return@LaunchedEffect
        }
        var lastSample = latestSampleMs
        var sampleFrameNanos = -1L
        var lastFrameNanos = -1L
        var display = latestSampleMs.toDouble()
        while (true) {
            withFrameNanos { now ->
                val sample = latestSampleMs
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
        lines = lines,
        isOverallRtl = isOverallRtl,
        normalStyle = normalStyle,
        accompanimentStyle = accompanimentStyle,
        currentPositionMs = currentPosition,
        isPlaying = state.isPlaying,
        onSeek = { lineTimeMs ->
            // Inverse of the highlight shift: tap targets audio time.
            player.seekTo((lineTimeMs - lyricsOffsetMs).coerceAtLeast(0L))
        },
        lyricsOffsetMs = lyricsOffsetMs,
        subLines = subLines,
        subReveal = subReveal,
        subLineStyle = subLineStyle,
        modifier = modifier,
    )
}

/**
 * Leaf host for transport controls: owns the 60ms seekbar clock so the panel
 * above stays static. No behavior change, strictly fewer recompositions.
 */
@Composable
private fun ModernLyricsControlsHost(
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
private fun ModernLyricsControls(
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = buttonsAlpha }
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onOpenLyricsSource != null) {
                val sourceInteraction = remember { MutableInteractionSource() }
                val isSourcePressed by sourceInteraction.collectIsPressedAsState()
                val sourceScale by animateFloatAsState(
                    targetValue = if (isSourcePressed) 0.82f else 1.0f,
                    animationSpec = ExpressiveMotion.spatialSpring(),
                    label = "sourceBtnScale",
                )
                IconButton(
                    onClick = onOpenLyricsSource,
                    enabled = controlsVisible,
                    interactionSource = sourceInteraction,
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            scaleX = sourceScale
                            scaleY = sourceScale
                        }
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = sourceInteraction)
                        .background(
                            liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                        ),
                ) {
                    Icon(
                        Icons.Filled.Lyrics,
                        contentDescription = stringResource(R.string.ly_source_cd),
                        modifier = Modifier.size(22.dp),
                        tint = Color.White.copy(alpha = 0.90f),
                    )
                }

                Spacer(Modifier.width(10.dp))
            }

            // Translation and romanization toggles sit left of the size
            // controls. The active mode's button tints with the theme colour
            // and turns itself off on a second tap; while its request is in
            // flight it shows the standard inline spinner.
            val translationActive = displayMode == LyricsDisplayMode.Translated
            val translateInteraction = remember { MutableInteractionSource() }
            val isTranslatePressed by translateInteraction.collectIsPressedAsState()
            val translatePressScale by animateFloatAsState(
                targetValue = if (isTranslatePressed) 0.82f else 1.0f,
                animationSpec = ExpressiveMotion.spatialSpring(),
                label = "translateBtnScale",
            )
            IconButton(
                onClick = onToggleTranslation,
                enabled = controlsVisible && translationAvailable,
                interactionSource = translateInteraction,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = translatePressScale
                        scaleY = translatePressScale
                    }
                    .clip(CircleShape)
                    .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = translateInteraction)
                    .background(
                        liquidGlassContainerColor(
                            if (translationActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                            else Color.White.copy(alpha = 0.14f)
                        ),
                    ),
            ) {
                if (translationLoading) {
                    ExpressiveInlineLoadingIndicator(
                        size = 20.dp,
                        strokeWidth = 2.2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(
                        Icons.Filled.Translate,
                        contentDescription = stringResource(
                            if (translationActive) R.string.ly_show_original_cd else R.string.ly_translate_cd
                        ),
                        modifier = Modifier.size(22.dp),
                        tint = if (translationActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.90f),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            val romanizationActive = displayMode == LyricsDisplayMode.Romanized
            val romanizeInteraction = remember { MutableInteractionSource() }
            val isRomanizePressed by romanizeInteraction.collectIsPressedAsState()
            val romanizePressScale by animateFloatAsState(
                targetValue = if (isRomanizePressed) 0.82f else 1.0f,
                animationSpec = ExpressiveMotion.spatialSpring(),
                label = "romanizeBtnScale",
            )
            IconButton(
                onClick = onToggleRomanization,
                enabled = controlsVisible && translationAvailable,
                interactionSource = romanizeInteraction,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = romanizePressScale
                        scaleY = romanizePressScale
                    }
                    .clip(CircleShape)
                    .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = romanizeInteraction)
                    .background(
                        liquidGlassContainerColor(
                            if (romanizationActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                            else Color.White.copy(alpha = 0.14f)
                        ),
                    ),
            ) {
                if (romanizationLoading) {
                    ExpressiveInlineLoadingIndicator(
                        size = 20.dp,
                        strokeWidth = 2.2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = stringResource(
                            if (romanizationActive) R.string.ly_show_original_cd else R.string.ly_romanize_cd
                        ),
                        modifier = Modifier.size(22.dp),
                        tint = if (romanizationActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.90f),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            if (canShareLyrics) {
                val shareInteraction = remember { MutableInteractionSource() }
                val isSharePressed by shareInteraction.collectIsPressedAsState()
                val sharePressScale by animateFloatAsState(
                    targetValue = if (isSharePressed) 0.82f else 1.0f,
                    animationSpec = ExpressiveMotion.spatialSpring(),
                    label = "shareBtnScale",
                )
                IconButton(
                    onClick = onShareLyrics,
                    enabled = controlsVisible,
                    interactionSource = shareInteraction,
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            scaleX = sharePressScale
                            scaleY = sharePressScale
                        }
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = shareInteraction)
                        .background(
                            liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                        ),
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.lyshare_button_cd),
                        modifier = Modifier.size(22.dp),
                        tint = Color.White.copy(alpha = 0.90f),
                    )
                }

                Spacer(Modifier.width(10.dp))
            }

            // Lyrics Font Scale toggle button placed immediately to the LEFT of Full Screen
            val fontInteraction = remember { MutableInteractionSource() }
            val isFontPressed by fontInteraction.collectIsPressedAsState()
            val fontScaleAnim by animateFloatAsState(
                targetValue = if (isFontPressed) 0.82f else 1.0f,
                animationSpec = ExpressiveMotion.spatialSpring(),
                label = "fontScaleAnim",
            )
            val isCustomFont = lyricsFontScale != 1.0f
            IconButton(
                onClick = { onShowFontSliderChange(!showFontSlider) },
                enabled = controlsVisible,
                interactionSource = fontInteraction,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = fontScaleAnim
                        scaleY = fontScaleAnim
                    }
                    .clip(CircleShape)
                    .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = fontInteraction)
                    .background(
                        liquidGlassContainerColor(
                            if (showFontSlider || isCustomFont) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                            else Color.White.copy(alpha = 0.14f)
                        ),
                    ),
            ) {
                Icon(
                    Icons.Filled.FormatSize,
                    contentDescription = stringResource(R.string.ly_size_cd),
                    modifier = Modifier.size(22.dp),
                    tint = if (showFontSlider || isCustomFont) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.90f),
                )
            }

            Spacer(Modifier.width(10.dp))

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
            }
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
                "−${formatTime((totalDurationMs - shown.toLong()).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Lift envelope (0..1) for a syllable: rises as it starts being sung, holds
 * while sung, then settles back after it ends, so a soft wave travels across
 * the line. Purely visual; derived from the same timestamps as the wipe.
 */
private fun syllableLift(syl: LyricSyllable, pos: Long): Float {
    if (pos < syl.timeMs) return 0f
    val dur = syl.durationMs.coerceAtLeast(1L)
    val rise = ((pos - syl.timeMs).toFloat() / dur.coerceIn(140L, 420L)).coerceIn(0f, 1f)
    val riseInv = 1f - rise
    val riseE = 1f - riseInv * riseInv * riseInv
    val after = pos - (syl.timeMs + dur)
    val settle = if (after <= 0L) 1f else (1f - after / 520f).coerceIn(0f, 1f)
    val settleE = settle * settle * (3f - 2f * settle)
    return riseE * settleE
}

/**
 * Draws the text content with each currently-lifting syllable translated up
 * by up to [liftPx]. Syllables wrapping across visual lines are not lifted.
 */
private fun ContentDrawScope.drawWithSyllableLift(
    l: TextLayoutResult,
    line: LyricLine,
    charRanges: List<Pair<Int, Int>>,
    pos: Long,
    liftPx: Float,
    isRtl: Boolean,
) {
    val textLength = l.layoutInput.text.length
    var rects: ArrayList<FloatArray>? = null
    for (i in line.syllables.indices) {
        val lift = syllableLift(line.syllables[i], pos)
        if (lift <= 0.001f) continue
        val (cs, ce) = charRanges.getOrNull(i) ?: continue
        if (ce <= cs || cs >= textLength) continue
        val last = (ce - 1).coerceAtMost(textLength - 1)
        val li = l.getLineForOffset(cs)
        if (li != l.getLineForOffset(last)) continue
        val xs = l.getHorizontalPosition(cs, true)
        val xe = if (ce < textLength && l.getLineForOffset(ce) == li) {
            l.getHorizontalPosition(ce, true)
        } else if (isRtl) l.getLineLeft(li) else l.getLineRight(li)
        if (rects == null) rects = ArrayList(4)
        rects += floatArrayOf(
            minOf(xs, xe), l.getLineTop(li), maxOf(xs, xe), l.getLineBottom(li), -lift * liftPx,
        )
    }
    if (rects == null) {
        drawContent()
        return
    }
    val canvas = drawContext.canvas.nativeCanvas
    canvas.save()
    for (r in rects) canvas.clipOutRect(r[0], r[1], r[2], r[3])
    drawContent()
    canvas.restore()
    for (r in rects) {
        canvas.save()
        canvas.translate(0f, r[4])
        canvas.clipRect(r[0], r[1], r[2], r[3])
        drawContent()
        canvas.restore()
    }
}

/**
 * Animated pixel scroll for [LazyListState], which only ships instant
 * [LazyListState.scrollBy] and indexed [LazyListState.animateScrollToItem].
 * A cubic-bezier frame loop so the active-line follow glides rather than
 * jumping. Callers already guard with runCatching. Mirrors the classic
 * view's helper (same package, file-private there so duplicated here).
 */
private suspend fun LazyListState.animateScrollBy(pixels: Float, durationMs: Float = 560f) {
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

/** Follow-scroll duration by gap: short nudges stay crisp, whole-verse jumps
 *  glide, bounded to the calm 350–500ms band. */
private fun lyricScrollDurationMs(deltaPx: Float): Float =
    (LYRIC_SCROLL_MIN_MS + kotlin.math.abs(deltaPx) * 0.35f)
        .coerceAtMost(LYRIC_SCROLL_MAX_MS)

internal fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle
    }
    return low - 1
}

/**
 * Guarantees proper spacing between words across all providers.
 * If provider syllable tokens stripped whitespace, restores spaces using line.text as ground truth.
 */
internal fun resolveSyllableDisplayTexts(line: LyricLine): List<String> {
    val syllables = line.syllables
    if (syllables.isEmpty()) return emptyList()

    val fullText = line.text
    if (fullText.contains(' ') || fullText.contains('\u00A0')) {
        val result = mutableListOf<String>()
        var cursor = 0

        for (i in syllables.indices) {
            val syl = syllables[i]
            val cleanSyl = syl.text.trim()
            if (cleanSyl.isEmpty()) {
                result.add(syl.text)
                continue
            }

            val idx = fullText.indexOf(cleanSyl, startIndex = cursor, ignoreCase = true)
            if (idx >= 0) {
                val endIdx = idx + cleanSyl.length
                val hasTrailingSpace = endIdx < fullText.length && (fullText[endIdx] == ' ' || fullText[endIdx] == '\u00A0')
                if (hasTrailingSpace && i < syllables.size - 1) {
                    result.add("$cleanSyl ")
                    cursor = endIdx + 1
                } else {
                    result.add(cleanSyl)
                    cursor = endIdx
                }
            } else {
                if (i < syllables.size - 1) {
                    result.add("$cleanSyl ")
                } else {
                    result.add(cleanSyl)
                }
            }
        }
        return result
    }

    return syllables.map { it.text }
}

