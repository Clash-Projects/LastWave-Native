package com.lastwave.app.ui.player

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.R
import com.lastwave.app.data.local.LyricsProvider
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricsSyncType
import com.lastwave.app.data.lyrics.LyricsTranslation
import com.lastwave.app.data.lyrics.providerForSource
import com.lastwave.app.data.lyrics.translationLanguageName
import com.lastwave.app.data.lyrics.translationWireTarget
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.player.lyricsshare.ShareLyricsDialog
import com.lastwave.app.ui.player.lyricsshare.TrackInfo
import com.lastwave.app.ui.player.lyricsshare.buildLyricsSnapshot
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LocalUseBoldFont
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import com.lastwave.app.ui.theme.lyricsBackingFontFamily
import com.lastwave.app.ui.theme.lyricsLeadFontFamily
import com.lastwave.app.ui.theme.lyricsSubLineFontFamily
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

/**
 * The lyrics screen's own synced list, placed in the Now Playing art area:
 * same rows, styles, word wipe, glow and motion. Styles mirror
 * [ModernLyricsPanel]'s exactly so both screens read 1:1.
 */
@Composable
internal fun PlayerInlineLyrics(
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    progressState: StateFlow<PlaybackProgressState>,
    lyricsOffsetMs: Long,
    fontScale: Float,
    subLines: List<String?>?,
    displayMode: LyricsDisplayMode,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val track = state.current ?: return
    // Sub-lines grow in and out together, as on the lyrics screen.
    val subReveal = remember { Animatable(if (subLines != null) 1f else 0f) }
    var shownSubLines by remember { mutableStateOf(subLines) }
    LaunchedEffect(subLines) {
        if (subLines == null) {
            subReveal.animateTo(0f, tween(LYRIC_SUBLINE_CLOSE_MS, easing = FastOutSlowInEasing))
            shownSubLines = null
        } else {
            shownSubLines = subLines
            subReveal.animateTo(1f, tween(LYRIC_SUBLINE_OPEN_MS, easing = FastOutSlowInEasing))
        }
    }
    val progressInitial = PlaybackProgressState(positionMs = state.positionMs, durationMs = state.durationMs)
    LyricsStateContent(
        lyricsState = lyricsState,
        signature = rememberLyricsSignature(track, lyricsState, accentColor),
        transitionSpec = { fadeIn(tween(ExpressiveMotion.Quick * 2)) togetherWith fadeOut(tween(ExpressiveMotion.Quick * 2)) },
        label = "playerLyricsState",
        modifier = modifier,
    ) { target ->
        when (target) {
            is LyricsUiState.Idle -> Box(Modifier.fillMaxSize())
            is LyricsUiState.Loading -> LyricsSignatureStatus(SignatureMode.Loading)
            is LyricsUiState.Empty, is LyricsUiState.Error -> LyricsSignatureStatus(SignatureMode.NotFound, onRetry)
            is LyricsUiState.Success -> when {
                target.isInstrumental -> ModernEmptyLyricsView(isInstrumental = true, onRetry = onRetry)
                target.isSynced && target.lines.isNotEmpty() -> {
                    val isWordSynced = target.isWordSynced ||
                        remember(target.lines) { target.lines.any { it.hasSyllables } }
                    val isOverallRtl = remember(target.lines) {
                        val meaningful = target.lines.filter { it.text.isNotBlank() && it.text != "♪" }
                        meaningful.isNotEmpty() && meaningful.count { it.isRtl } > meaningful.size / 2
                    }
                    val isAppleMusic = remember(target.source) {
                        target.source?.contains("Apple Music", ignoreCase = true) == true
                    }
                    val currentTextStyle = LocalTextStyle.current
                    val useAppFont = MaterialTheme.typography.bodyLarge.fontFamily != FontFamily.Default
                    val boldFont = LocalUseBoldFont.current
                    val baseSize = (if (isAppleMusic) 30f else if (isWordSynced) 34f else 32f) * fontScale
                    val normalStyle = remember(currentTextStyle, baseSize, useAppFont, boldFont) {
                        currentTextStyle.copy(
                            fontFamily = if (useAppFont) lyricsLeadFontFamily(boldFont) else FontFamily.Default,
                            fontSize = baseSize.sp,
                            lineHeight = (baseSize * 1.20f).sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.6).sp,
                            textMotion = TextMotion.Animated,
                        )
                    }
                    val accompanimentStyle = remember(currentTextStyle, baseSize, useAppFont, boldFont) {
                        val size = baseSize * 0.65f
                        currentTextStyle.copy(
                            fontFamily = if (useAppFont) lyricsBackingFontFamily(boldFont) else FontFamily.Default,
                            fontSize = size.sp,
                            lineHeight = (size * 1.22f).sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            textMotion = TextMotion.Animated,
                        )
                    }
                    val subLineStyle = remember(currentTextStyle, fontScale, useAppFont, boldFont) {
                        currentTextStyle.copy(
                            fontFamily = if (useAppFont) lyricsSubLineFontFamily(boldFont) else FontFamily.Default,
                            fontSize = (16f * fontScale).sp,
                            lineHeight = (22f * fontScale).sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.1).sp,
                            textMotion = TextMotion.Animated,
                        )
                    }
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (isOverallRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        SyncedLyricsProgressHost(
                            lines = target.lines,
                            isOverallRtl = isOverallRtl,
                            normalStyle = normalStyle,
                            accompanimentStyle = accompanimentStyle,
                            subLineStyle = subLineStyle,
                            subLines = shownSubLines,
                            subReveal = { subReveal.value },
                            displayMode = displayMode,
                            progressFlow = progressState,
                            progressInitial = progressInitial,
                            state = state,
                            player = player,
                            lyricsOffsetMs = lyricsOffsetMs,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                !target.plainLyrics.isNullOrBlank() -> ModernPlainLyricsView(
                    plainLyrics = target.plainLyrics,
                    lyricsFontScale = fontScale,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LyricsSignatureStatus(SignatureMode.NotFound, onRetry)
            }
        }
    }
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
