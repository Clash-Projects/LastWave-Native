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

/** Which secondary script the floating buttons currently draw under each
 *  line: none ([Original]), the pronunciation ([Romanized]) or a translation
 *  ([Translated]). At most one mode is shown at a time. */
private enum class LyricsDisplayMode { Original, Romanized, Translated }

/** Line falloff by distance from the active row: alpha and soften radius.
 *  Past the end of the array the last value holds, so distance never needs a
 *  branch. The active line's own scale (1.0) lives with the row code. */
private val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.50f, 0.40f, 0.30f, 0.22f)

/** Inactive rows draw at this scale drop (0.97 at one row away and beyond). */
private const val LYRIC_INACTIVE_SCALE_DROP = 0.03f

/** Shared focus glide between rows. */
private const val LYRIC_FOCUS_MS = 420

/** Elastic trail: per-row settle, per-row stagger, and the whole clock. */
private const val LYRIC_TRAIL_SETTLE_MS = 560f
private const val LYRIC_TRAIL_STAGGER_MS = 38f
private const val LYRIC_TRAIL_TOTAL_MS = 560 + 6 * 38

/** The sung row is anchored this far from the top of the viewport. */
private const val LYRIC_ANCHOR_FRACTION = 0.18f

/** Spacing inside a stanza and the breath between stanzas. */
private val LYRIC_LINE_GAP = 20.dp
private val LYRIC_STANZA_GAP = 40.dp

/** Backing vocals: shift below the lead and strength relative to it. */
private val LYRIC_BACKING_SHIFT = 7.5.dp
private const val LYRIC_BACKING_EMPHASIS = 0.78f

/** Duet staging widths: one voice per side, unison rows a little wider. */
private const val LYRIC_DUET_WIDTH = 0.75f
private const val LYRIC_UNISON_WIDTH = 0.85f

/** Text alpha of rows that are not being sung (row alpha falls off on top). */
private const val LYRIC_IDLE_TEXT_ALPHA = 0.85f

/** Unsung part of a lit word-synced row; matches an idle row one step away. */
private const val LYRIC_LIT_BASE_ALPHA = 0.42f

/** How far a syllable rises while it is sung. */
private val LYRIC_LIFT = 3.dp

/** Height of the instrumental-break dots row while it is open. */
private val LYRIC_INTERLUDE_HEIGHT = 44.dp

/** Glide curve for the follow scroll and row scale: quick departure, long
 *  soft landing â€” closer to a platform sheet than to a linear scroll. */
private val LYRIC_EASE = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)

/** Follow-scroll duration: small corrections stay crisp, whole-verse jumps
 *  glide. Gap (px between the row and the focus point) picks the point inside
 *  the band; the band itself is what keeps motion calm. */
private const val LYRIC_SCROLL_MIN_MS = 350f
private const val LYRIC_SCROLL_MAX_MS = 500f

/** Sub-line reveal: slightly unhurried in, clipped and quick out. */
private const val LYRIC_SUBLINE_OPEN_MS = 460
private const val LYRIC_SUBLINE_CLOSE_MS = 260

/** Line-synced clock: longest gap it bridges with a timed wake before the
 *  next playhead sample (samples arrive every ~60ms while visible); the
 *  same 250ms cap the frame clock uses for extrapolation. */
private const val LINE_BOUNDARY_WAKE_WINDOW_MS = 250L

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
private fun ModernSyncedLyricsList(
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

/** Which side of the column a row is staged on, in reading direction. */
private enum class LyricSide { Start, End, Center }

/** Alpha for a row [dist] items away from the focus, interpolated. */
private fun lyricFalloffAlpha(dist: Float): Float {
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
private fun lyricTrailOffsetDp(rel: Int, clock: Float): Float {
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
private fun LyricGroupRow(
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
private fun LyricInterludeRow(
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
private fun LyricSubLine(
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
 * One vocal run (lead or backing). Unlit rows and line-synced rows are a
 * single Text; a lit word-synced row is a dim base plus a bright copy wiped
 * by the karaoke mask.
 */
@Composable
private fun KaraokeVocal(
    vocal: StagedVocal,
    lit: Boolean,
    currentPositionMs: () -> Long,
    style: TextStyle,
    textAlign: TextAlign,
    emphasis: Float,
    glow: Boolean,
    modifier: Modifier = Modifier,
) {
    val direction = if (vocal.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        if (lit && vocal.isWordSynced) {
            KaraokeFillText(
                vocal = vocal,
                currentPositionMs = currentPositionMs,
                style = style,
                textAlign = textAlign,
                emphasis = emphasis,
                glow = glow,
                modifier = modifier,
            )
        } else {
            val drawStyle = remember(style, lit, glow) {
                // On S+ the glow is a blurred copy of the same Text (see
                // LyricGlowText); the shadow is only the pre-S fallback.
                if (lit && glow && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                    style.copy(shadow = LYRIC_FALLBACK_SHADOW)
                } else style
            }
            Box(modifier) {
                if (lit && glow) {
                    LyricGlowText(text = vocal.text, style = style, textAlign = textAlign)
                }
                Text(
                    text = vocal.text,
                    style = drawStyle,
                    color = Color.White.copy(alpha = (if (lit) 1f else LYRIC_IDLE_TEXT_ALPHA) * emphasis),
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val LYRIC_FALLBACK_SHADOW = Shadow(
    color = Color.White.copy(alpha = 0.50f),
    blurRadius = 22f,
    offset = Offset.Zero,
)

@Composable
private fun KaraokeFillText(
    vocal: StagedVocal,
    currentPositionMs: () -> Long,
    style: TextStyle,
    textAlign: TextAlign,
    emphasis: Float,
    glow: Boolean,
    modifier: Modifier = Modifier,
) {
    val isRtl = vocal.isRtl
    val units = vocal.units
    val charRanges = vocal.charRanges
    val fullText = vocal.text
    val fillStyle = remember(style, glow) {
        if (glow && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            style.copy(shadow = LYRIC_FALLBACK_SHADOW)
        } else style
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
    val gradientBounds = remember { FloatArray(2) { -1f } }
    val maskGradients = remember { KaraokeMaskGradients() }
    val gradientIsRtl = remember { BooleanArray(2) } // [0] = bound, [1] = rtl

    Box(modifier = modifier) {
        if (glow) {
            LyricGlowText(text = fullText, style = style, textAlign = textAlign)
        }
        // Dim base: the whole row, with each syllable lifting as it is sung.
        Text(
            text = fullText,
            style = style,
            color = Color.White.copy(alpha = LYRIC_LIT_BASE_ALPHA * emphasis),
            textAlign = textAlign,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val l = layout
                    if (l == null) drawContent()
                    else drawWithSyllableLift(l, units, charRanges, currentPositionMs(), LYRIC_LIFT.toPx(), isRtl)
                },
        )
        // Bright copy, revealed up to the sung extent with a feathered edge.
        Text(
            text = fullText,
            style = fillStyle,
            color = Color.White.copy(alpha = emphasis),
            textAlign = textAlign,
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
                    for (i in units.indices) {
                        val syl = units[i]
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
                    if (fill <= 0f || sylIdx < 0) return@drawWithContent
                    val liftPx = LYRIC_LIFT.toPx()
                    if (fill >= textLength) {
                        drawWithSyllableLift(l, units, charRanges, pos, liftPx, isRtl)
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
                        // Skip the separator space so the wipe starts on the glyphs.
                        var glyphStart = startIdx
                        while (glyphStart < lastIdx && fullText[glyphStart] == ' ') glyphStart++
                        val xs = l.getHorizontalPosition(glyphStart, true)
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
                    // Lifted glyphs rise above the row; widen the band so the
                    // unsung part of a rising syllable stays masked.
                    val top = l.getLineTop(lineIdx) - liftPx * 1.5f
                    val bottom = l.getLineBottom(lineIdx)
                    val canvas = drawContext.canvas.nativeCanvas

                    // Visual rows above the one being sung are fully sung:
                    // draw them straight, no offscreen layer.
                    if (lineIdx > 0 && top > 0f) {
                        val saved = canvas.save()
                        canvas.clipRect(-size.width, -size.height, size.width * 2f, top)
                        drawWithSyllableLift(l, units, charRanges, pos, liftPx, isRtl)
                        canvas.restoreToCount(saved)
                    }

                    // Offscreen layer bounded to the sung visual row only.
                    val checkpoint = canvas.saveLayer(0f, top, size.width, bottom, null)
                    drawWithSyllableLift(l, units, charRanges, pos, liftPx, isRtl)
                    val xStart = (x - feather).coerceAtLeast(0f)
                    val xEnd = (x + feather).coerceAtMost(size.width)
                    if (isRtl) {
                        if (xStart > 0f) canvas.drawRect(0f, top, xStart, bottom, clearPaint)
                    } else {
                        if (xEnd < size.width) canvas.drawRect(xEnd, top, size.width, bottom, clearPaint)
                    }
                    if (xEnd > xStart) {
                        if (gradientBounds[0] != xStart || gradientBounds[1] != xEnd ||
                            !gradientIsRtl[0] || gradientIsRtl[1] != isRtl
                        ) {
                            gradientBounds[0] = xStart
                            gradientBounds[1] = xEnd
                            gradientIsRtl[0] = true
                            gradientIsRtl[1] = isRtl
                            maskPaint.shader = null // re-bind so the moved local matrix is picked up on every API level
                            maskPaint.shader = maskGradients.place(rtl = isRtl, xStart, xEnd)
                        }
                        canvas.drawRect(xStart, top, xEnd, bottom, maskPaint)
                    }
                    canvas.restoreToCount(checkpoint)
                },
        )
    }
}

/**
 * Feather gradients for the karaoke wipe. The edge moves every frame, and a
 * fresh LinearGradient per frame churned the GC during word-synced lyrics.
 * Instead one unit-width gradient per direction is created once and only its
 * local matrix is moved/stretched to [xStart, xEnd]; CLAMP tiling makes it
 * render identically to a gradient built at those coordinates.
 */
private class KaraokeMaskGradients {
    private val ltr = android.graphics.LinearGradient(
        0f, 0f, 1f, 0f,
        android.graphics.Color.WHITE,
        android.graphics.Color.TRANSPARENT,
        android.graphics.Shader.TileMode.CLAMP,
    )
    private val rtl = android.graphics.LinearGradient(
        0f, 0f, 1f, 0f,
        android.graphics.Color.TRANSPARENT,
        android.graphics.Color.WHITE,
        android.graphics.Shader.TileMode.CLAMP,
    )
    private val matrix = android.graphics.Matrix()

    fun place(rtl: Boolean, xStart: Float, xEnd: Float): android.graphics.Shader {
        val shader = if (rtl) this.rtl else ltr
        matrix.setScale((xEnd - xStart).coerceAtLeast(0.001f), 1f)
        matrix.postTranslate(xStart, 0f)
        shader.setLocalMatrix(matrix)
        return shader
    }
}

/** Radius/alpha of the active line's glow, kept in one place. */
private val LYRIC_GLOW_RADIUS = 8.dp
private const val LYRIC_GLOW_ALPHA = 0.5f

/**
 * The active line's glow: a blurred copy of the very Text drawn on top of it.
 *
 * A TextStyle.shadow is positioned by the platform's per-glyph shadow pass,
 * which drifts off complex-script shaping â€” on Gurmukhi the halo lands offset
 * from the glyphs and reads as a second, wrong image behind the line. A
 * layout-identical duplicate (same text, style, direction and width) has the
 * same bounds by construction, for every script.
 *
 * Below API 31 Modifier.blur is a no-op, so the caller keeps the shadow there
 * and this draws nothing. An unbounded edge treatment keeps the halo from
 * being cut off at the Text's own bounds.
 */
@Composable
private fun LyricGlowText(text: String, style: TextStyle, textAlign: TextAlign = TextAlign.Start) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
    Text(
        text = text,
        style = style,
        color = Color.White.copy(alpha = LYRIC_GLOW_ALPHA),
        textAlign = textAlign,
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
                    fontFamily = if (MaterialTheme.typography.bodyLarge.fontFamily != androidx.compose.ui.text.font.FontFamily.Default) {
                        com.lastwave.app.ui.theme.LyricsBackingFontFamily
                    } else {
                        androidx.compose.ui.text.font.FontFamily.Default
                    },
                    fontSize = (28f * lyricsFontScale).sp,
                    lineHeight = (40f * lyricsFontScale).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
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
 * with no time quantization â€” word fills step at syllable granularity
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
    units: List<LyricSyllable>,
    charRanges: List<Pair<Int, Int>>,
    pos: Long,
    liftPx: Float,
    isRtl: Boolean,
) {
    val textLength = l.layoutInput.text.length
    var rects: ArrayList<FloatArray>? = null
    for (i in units.indices) {
        val lift = syllableLift(units[i], pos)
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
 *  glide, bounded to the calm 350â€“500ms band. */
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

@Composable
private fun LyricsOptionsMenuDialog(
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

