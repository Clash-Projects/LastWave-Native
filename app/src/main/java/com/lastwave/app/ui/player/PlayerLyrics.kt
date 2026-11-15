package com.lastwave.app.ui.player

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.local.LyricsProvider
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricsSyncType
import com.lastwave.app.data.lyrics.LyricsTranslation
import com.lastwave.app.data.lyrics.VocalRole
import com.lastwave.app.data.lyrics.providerForSource
import com.lastwave.app.data.lyrics.translationLanguageName
import com.lastwave.app.data.lyrics.translationWireTarget
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.player.lyricsshare.ShareLyricsDialog
import com.lastwave.app.ui.player.lyricsshare.TrackInfo
import com.lastwave.app.ui.player.lyricsshare.buildLyricsSnapshot
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * What Player Lyrics shows on top of the shared lyrics: the sub-line script
 * and the text size preview. It calls the same cached [LyricsTranslation]
 * the lyrics tab uses, but keeps its own copy of the mode so the lyrics tab's
 * state is never written from here.
 */
@Stable
internal class PlayerLyricsUiState(private val scope: CoroutineScope, private val context: Context) {
    var displayMode by mutableStateOf(LyricsDisplayMode.Original)
        private set
    var subLines by mutableStateOf<List<String?>?>(null)
        private set
    var fontScale by mutableFloatStateOf(1f)
    var lines by mutableStateOf<List<LyricLine>?>(null)
        private set
    private var trackKey = ""
    private var targetLanguage = ""
    private var job: Job? = null

    fun bind(trackKey: String, lines: List<LyricLine>?, translationLanguage: String) {
        job?.cancel()
        job = null
        this.trackKey = trackKey
        this.lines = lines
        targetLanguage = translationWireTarget(translationLanguage)
        displayMode = LyricsDisplayMode.Original
        subLines = null
    }

    fun toggle(target: LyricsDisplayMode) {
        val lines = lines ?: return
        job?.cancel()
        job = null
        if (displayMode == target) {
            displayMode = LyricsDisplayMode.Original
            subLines = null
            return
        }
        val key = trackKey
        val language = targetLanguage
        job = scope.launch {
            val result = when (target) {
                LyricsDisplayMode.Translated -> when (val r = LyricsTranslation.translate(key, lines, language)) {
                    is LyricsTranslation.Result.Translated -> r.subLines
                    is LyricsTranslation.Result.SameLanguage -> toast(
                        context.getString(
                            R.string.ly_toast_already_language,
                            translationLanguageName(r.language, Locale.getDefault()),
                        ),
                    )
                    LyricsTranslation.Result.Unavailable -> toast(context.getString(R.string.ly_toast_translation_unavailable))
                }
                LyricsDisplayMode.Romanized ->
                    lines.takeIf { list -> list.any { !it.transliteration.isNullOrBlank() } }?.map { it.transliteration }
                        ?: when (val r = LyricsTranslation.romanize(key, lines)) {
                            is LyricsTranslation.RomanizationResult.Romanized -> r.subLines
                            LyricsTranslation.RomanizationResult.AlreadyRomanized -> toast(context.getString(R.string.ly_toast_already_romanized))
                            LyricsTranslation.RomanizationResult.Unavailable -> toast(context.getString(R.string.ly_toast_romanization_unavailable))
                        }
                LyricsDisplayMode.Original -> null
            }
            if (result != null) {
                subLines = result
                displayMode = target
            }
        }
    }

    private fun toast(message: String): List<String?>? {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return null
    }
}

@Composable
internal fun rememberPlayerLyricsUiState(
    trackKey: String,
    lyricsState: LyricsUiState,
    translationLanguage: String,
    lyricsFontScale: Float,
): PlayerLyricsUiState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { PlayerLyricsUiState(scope, context) }
    // Same eligibility as the lyrics tab: only synced, sung lyrics translate.
    val lines = (lyricsState as? LyricsUiState.Success)
        ?.takeIf { it.isSynced && it.lines.isNotEmpty() && !it.isInstrumental }
        ?.lines
    LaunchedEffect(trackKey, lines, translationLanguage) { holder.bind(trackKey, lines, translationLanguage) }
    LaunchedEffect(lyricsFontScale) { holder.fontScale = lyricsFontScale }
    return holder
}

/** Lyrics centred in the Now Playing art area: the sung line leads, its neighbours recede. */
@Composable
internal fun PlayerInlineLyrics(
    lyricsState: LyricsUiState,
    progressState: StateFlow<PlaybackProgressState>,
    lyricsOffsetMs: Long,
    fontScale: Float,
    subLines: List<String?>?,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = lyricsState,
        animationSpec = tween(ExpressiveMotion.Quick * 2),
        label = "playerLyricsState",
        modifier = modifier,
    ) { target ->
        when (target) {
            is LyricsUiState.Idle -> Box(Modifier.fillMaxSize())
            is LyricsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ExpressiveInlineLoadingIndicator(size = 36.dp, strokeWidth = 3.dp, color = Color.White)
            }
            is LyricsUiState.Empty, is LyricsUiState.Error -> ModernEmptyLyricsView(isInstrumental = false, onRetry = onRetry)
            is LyricsUiState.Success -> when {
                target.isInstrumental -> ModernEmptyLyricsView(isInstrumental = true, onRetry = onRetry)
                target.isSynced && target.lines.any { it.text.isNotBlank() } -> CenteredSyncedLyrics(
                    lines = target.lines,
                    progressState = progressState,
                    lyricsOffsetMs = lyricsOffsetMs,
                    fontScale = fontScale,
                    subLines = subLines,
                    onSeek = onSeek,
                )
                !target.plainLyrics.isNullOrBlank() -> CenteredPlainLyrics(target.plainLyrics, fontScale)
                else -> ModernEmptyLyricsView(isInstrumental = false, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun lyricTextStyle(fontScale: Float): TextStyle {
    val base = MaterialTheme.typography.headlineMedium
    return remember(base, fontScale) {
        val size = 28f * fontScale
        base.copy(
            fontSize = size.sp,
            lineHeight = (size * 1.18f).sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredSyncedLyrics(
    lines: List<LyricLine>,
    progressState: StateFlow<PlaybackProgressState>,
    lyricsOffsetMs: Long,
    fontScale: Float,
    subLines: List<String?>?,
    onSeek: (Long) -> Unit,
) {
    // Blank rows are instrumental breaks and backing vocals nest under their
    // lead, so neither gets a row of its own here.
    val rows = remember(lines) {
        lines.indices.filter { lines[it].text.isNotBlank() && lines[it].role != VocalRole.BACKGROUND_SUBLINE }
    }
    // Playhead samples land in a state read only by derivedStateOf below, so
    // a 60 ms tick recomposes nothing unless the sung row (or word) changes.
    val position = remember { mutableLongStateOf(progressState.value.positionMs) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(progressState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            progressState.collect { position.longValue = it.positionMs }
        }
    }
    val offset by rememberUpdatedState(lyricsOffsetMs)
    val shiftedPosition: () -> Long = remember { { position.longValue + offset } }
    val activeSource by remember(lines) { derivedStateOf { activeLineIndex(lines, shiftedPosition()) } }
    // Last row at or before the sung line; it keeps the scroll anchored
    // through a break instead of jumping back to the top.
    val focusRow = remember(rows, activeSource) {
        val i = rows.binarySearch(activeSource)
        if (i >= 0) i else -i - 2
    }
    val inBreak = activeSource >= 0 && lines[activeSource].text.isBlank()
    val highlightRow = if (inBreak) -1 else focusRow

    val style = lyricTextStyle(fontScale)
    val subStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = (17f * fontScale).sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var holdAutoScroll by remember { mutableStateOf(false) }
    // A user scroll wins for a moment, then the sung line takes the list back.
    LaunchedEffect(dragged) {
        if (dragged) {
            holdAutoScroll = true
        } else if (holdAutoScroll) {
            delay(USER_SCROLL_HOLD_MS)
            holdAutoScroll = false
        }
    }
    val target = focusRow.coerceAtLeast(0)
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target, holdAutoScroll) {
        if (holdAutoScroll) return@LaunchedEffect
        if (!placed) {
            listState.scrollToItem(target)
            placed = true
            return@LaunchedEffect
        }
        // Item offset 0 is the top of the content area, i.e. the anchor line.
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        if (item != null) {
            listState.animateScrollBy(item.offset.toFloat(), tween(LYRIC_FOCUS_MS, easing = FastOutSlowInEasing))
        } else {
            listState.animateScrollToItem(target)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val anchor = maxHeight * LYRIC_ANCHOR
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight - anchor),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(EdgeFade, blendMode = BlendMode.DstIn)
                },
        ) {
            itemsIndexed(rows, key = { _, source -> source }) { row, source ->
                val distance = if (highlightRow < 0) abs(row - focusRow) + 1 else abs(row - highlightRow)
                CenteredLyricRow(
                    line = lines[source],
                    active = row == highlightRow,
                    falloff = LINE_FALLOFF_ALPHA[distance.coerceAtMost(LINE_FALLOFF_ALPHA.lastIndex)],
                    subLine = subLines?.getOrNull(source),
                    style = style,
                    subStyle = subStyle,
                    position = shiftedPosition,
                    onClick = hapticClick { onSeek(lines[source].timeMs) },
                )
            }
        }
    }
}

@Composable
private fun CenteredLyricRow(
    line: LyricLine,
    active: Boolean,
    falloff: Float,
    subLine: String?,
    style: TextStyle,
    subStyle: TextStyle,
    position: () -> Long,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(falloff, tween(LYRIC_FOCUS_MS), label = "playerLyricAlpha")
    val scale by animateFloatAsState(
        if (active) 1f else INACTIVE_SCALE,
        ExpressiveMotion.spatialSpring(),
        label = "playerLyricScale",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (active && line.hasSyllables) {
            WordSyncedLine(line, style, position)
        } else {
            Text(line.text, style = style, color = Color.White)
        }
        if (!subLine.isNullOrBlank()) {
            Text(
                subLine,
                style = subStyle,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The sung words are lit, the rest wait dimmed; recomposes once per word, not per frame. */
@Composable
private fun WordSyncedLine(line: LyricLine, style: TextStyle, position: () -> Long) {
    val words = remember(line) { line.syllables.filterNot { it.isBackground } }
    val parts = remember(words, line.text) { renderedSyllableContents(words, needsSpacing = line.text.contains(' ')) }
    val sung by remember(words) { derivedStateOf { position().let { p -> words.count { it.timeMs <= p } } } }
    val text = remember(parts, sung) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White)) { append(parts.take(sung).joinToString("")) }
            withStyle(SpanStyle(color = Color.White.copy(alpha = UNSUNG_WORD_ALPHA))) { append(parts.drop(sung).joinToString("")) }
        }
    }
    Text(text, style = style)
}

@Composable
private fun CenteredPlainLyrics(plainLyrics: String, fontScale: Float) {
    Text(
        plainLyrics,
        style = lyricTextStyle(fontScale).copy(fontSize = (22f * fontScale).sp, lineHeight = (30f * fontScale).sp),
        color = Color.White.copy(alpha = 0.88f),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

/**
 * Player Lyrics' Full Screen and options pair. Same look as the lyrics
 * screen's floating pair and the same [LyricsOptionsMenuDialog], source
 * picker and share sheet behind it; Full Screen opens the lyrics screen.
 */
@Composable
internal fun PlayerLyricsActions(
    ui: PlayerLyricsUiState,
    lyricsState: LyricsUiState,
    track: PlayableTrack,
    progressState: StateFlow<PlaybackProgressState>,
    lyricsOffsetMs: Long,
    resolvedArtworkUrl: String?,
    primaryColor: Color,
    onOpenFullLyrics: () -> Unit,
    onLyricsFontScaleChange: (Float) -> Unit,
    onOpenLyricsOffset: (() -> Unit)?,
    lyricsProviderStatuses: Map<LyricsProvider, LyricsSyncType>,
    onProbeLyricsProviders: (() -> Unit)?,
    onSelectLyricsProvider: ((LyricsProvider) -> Unit)?,
    onTryAnotherLyricsSource: (() -> Unit)?,
) {
    var showOptions by remember { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    var showShare by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LyricsGlassButton(Icons.Filled.Fullscreen, "Fullscreen lyrics", onOpenFullLyrics)
        Spacer(Modifier.width(10.dp))
        LyricsGlassButton(Icons.Filled.MoreVert, "Lyrics options") { showOptions = true }
    }
    if (showOptions) {
        LyricsOptionsMenuDialog(
            onDismiss = { showOptions = false },
            onOpenLyricsSource = if (onSelectLyricsProvider != null || onTryAnotherLyricsSource != null) {
                { showSource = true }
            } else null,
            canShareLyrics = lyricsState is LyricsUiState.Success &&
                !lyricsState.isInstrumental &&
                (lyricsState.lines.any { it.text.isNotBlank() } || !lyricsState.plainLyrics.isNullOrBlank()),
            onShareLyrics = { showShare = true },
            lyricsFontScale = ui.fontScale,
            onLyricsFontScaleChange = {
                ui.fontScale = it
                onLyricsFontScaleChange(it)
            },
            onLyricsFontScalePreview = { ui.fontScale = it },
            displayMode = ui.displayMode,
            translationAvailable = ui.lines != null,
            onToggleTranslation = { ui.toggle(LyricsDisplayMode.Translated) },
            onToggleRomanization = { ui.toggle(LyricsDisplayMode.Romanized) },
            lyricsOffsetMs = lyricsOffsetMs,
            onOpenLyricsOffset = onOpenLyricsOffset,
        )
    }
    if (showSource) {
        LaunchedEffect(Unit) { onProbeLyricsProviders?.invoke() }
        val currentSource = (lyricsState as? LyricsUiState.Success)?.source
        val currentProvider = remember(currentSource) { providerForSource(currentSource) }
        LyricsSourceDialog(
            currentSource = currentSource,
            currentProvider = currentProvider,
            providerStatuses = lyricsProviderStatuses,
            onSelectProvider = { provider ->
                showSource = false
                onSelectLyricsProvider?.invoke(provider)
            },
            onTryAnotherSource = {
                showSource = false
                onTryAnotherLyricsSource?.invoke()
            },
            onDismiss = { showSource = false },
        )
    }
    if (showShare) {
        // A share session keeps the song and line it opened with while playback moves on.
        val snapshot = remember {
            val lines = (lyricsState as? LyricsUiState.Success)?.lines.orEmpty()
            buildLyricsSnapshot(
                lyricsState,
                TrackInfo(track.title, track.artist, track.artworkUrl, resolvedArtworkUrl),
                if (lines.isNotEmpty()) activeLineIndex(lines, progressState.value.positionMs + lyricsOffsetMs) else -1,
            )
        }
        if (snapshot != null) {
            ShareLyricsDialog(snapshot = snapshot, onDismiss = { showShare = false }, primaryColor = primaryColor)
        }
    }
}

@Composable
private fun LyricsGlassButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1.0f,
        animationSpec = ExpressiveMotion.spatialSpring(),
        label = "playerLyricsButtonScale",
    )
    IconButton(
        onClick = hapticClick(action = onClick),
        interactionSource = interaction,
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = interaction)
            .background(liquidGlassContainerColor(Color.White.copy(alpha = 0.14f))),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp), tint = Color.White.copy(alpha = 0.90f))
    }
}

/** The sung line sits a bit above centre, where the eye rests over the title. */
private const val LYRIC_ANCHOR = 0.36f
private const val INACTIVE_SCALE = 0.94f
private const val UNSUNG_WORD_ALPHA = 0.42f
private const val USER_SCROLL_HOLD_MS = 2_500L
private val EdgeFade = Brush.verticalGradient(
    0f to Color.Transparent,
    0.14f to Color.Black,
    0.82f to Color.Black,
    1f to Color.Transparent,
)
