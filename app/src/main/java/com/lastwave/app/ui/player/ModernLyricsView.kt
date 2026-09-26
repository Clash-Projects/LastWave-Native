package com.lastwave.app.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable
import com.lastwave.app.data.lyrics.isRtlText
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.mocharealm.accompanist.lyrics.core.model.Artist
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

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
    modifier: Modifier = Modifier,
    /** Manual sync correction (ms, + = lyrics later). Applies to lyric
     *  focus/highlight only — the seekbar below keeps true position. */
    lyricsOffsetMs: Long = 0L,
) {
    val track = state.current ?: return

    val progress by (progressState ?: player.progressState).collectAsStateWithLifecycle(
        initialValue = PlaybackProgressState(positionMs = state.positionMs, durationMs = state.durationMs),
    )

    // Keyed on the whole track: videoId is null for local/search tracks,
    // and a null key would leak the previous song's smoothing state.
    var smoothedPositionMs by remember(track) { mutableLongStateOf(progress.positionMs) }

    LaunchedEffect(progress.positionMs, state.isPlaying, track) {
        val drift = kotlin.math.abs(smoothedPositionMs - progress.positionMs)
        // Hard snap on seek (>250ms drift) or when stopped/paused
        if (drift > 250 || !state.isPlaying) {
            smoothedPositionMs = progress.positionMs
        }
    }

    LaunchedEffect(state.isPlaying, track) {
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

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = lyricsState,
            transitionSpec = {
                (fadeIn(tween(ExpressiveMotion.Quick)) +
                    androidx.compose.animation.scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(ExpressiveMotion.Quick)) +
                        androidx.compose.animation.scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.96f))
            },
            label = "modernLyricsStateContent",
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
                        // Line-sync rows (no syllables) use the compact tier
                        // below; overlong word-sync rows are pre-split to the
                        // measured width so the large type never overflows
                        // off-screen. The list sits a little lower so the
                        // first line clears the header.
                        val isWordSynced = targetState.isWordSynced ||
                            remember(targetState.lines) { targetState.lines.any { it.hasSyllables } }
                        val isOverallRtl = remember(targetState.lines) {
                            val meaningful = targetState.lines.filter { it.text.isNotBlank() && it.text != "♪" }
                            if (meaningful.isEmpty()) false
                            else meaningful.count { it.isRtl } > meaningful.size / 2
                        }
                        // Apple Music word-sync rows run full-sentence wide, so
                        // they keep a compact size while other providers use
                        // the standard tier. Sizes are deliberately moderate:
                        // oversized type was the gap driver (fewer words fit,
                        // the splitter chopped rows, multiplied spacing).
                        // The wrap budget below is measured in these same
                        // styles so rows still clear the edges.
                        val isAppleMusic = remember(targetState.source) {
                            targetState.source?.contains("Apple Music", ignoreCase = true) == true
                        }
                        // Shared style instances: the splitter below measures
                        // with exactly this style, so its fit verdict matches
                        // what the canvas will draw.
                        val karaokeNormalStyle = LocalTextStyle.current.copy(
                            fontSize = if (isAppleMusic) 28.sp else if (isWordSynced) 32.sp else 30.sp,
                            fontWeight = FontWeight.Bold,
                            textMotion = TextMotion.Animated,
                        )
                        val karaokeAccompanimentStyle = LocalTextStyle.current.copy(
                            fontSize = if (isAppleMusic) 22.sp else if (isWordSynced) 24.sp else 22.sp,
                            fontWeight = FontWeight.Bold,
                            textMotion = TextMotion.Animated,
                        )

                        val layoutDirection = if (isOverallRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        // Short provider badge: makes it visible why words
                        // animate (word-sync) or just scroll (line-sync).
                        val syncLabel = remember(targetState.source, isWordSynced) {
                            val provider = targetState.source
                                ?.substringBefore(" (")
                                ?.takeIf { it.isNotBlank() } ?: "Lyrics"
                            "${if (isWordSynced) "WORD SYNC" else "LINE SYNC"} • $provider"
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
                                KaraokeLineWrapScope(
                                    lines = targetState.lines,
                                    isOverallRtl = isOverallRtl,
                                    trackTitle = track.title,
                                    trackArtist = track.artist,
                                    normalStyle = karaokeNormalStyle,
                                    accompanimentStyle = karaokeAccompanimentStyle,
                                    currentPosition = { (smoothedPositionMs + lyricsOffsetMs).toInt() },
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

        ModernLyricsControls(
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

/**
 * Horizontal chrome around karaoke glyphs, both sides combined: 12dp view
 * padding (Modifier.padding on the list below) + 16dp line padding inside
 * each karaoke row. Measured, not guessed: the old 128dp estimate plus a
 * 0.80 panic factor shrank the budget to ~185dp on a 360dp phone, chopping
 * nearly every line into 2-word rows.
 */
private val KaraokeHorizontalChrome = 56.dp

/**
 * Measures lines against the settled list width and pre-splits only
 * genuinely overlong ones into balanced sub-lines ([splitKaraokeToFit] for
 * word-sync rows, [splitLineSyncToFit] for line-sync rows) before the
 * karaoke canvas ever measures them. The budget keeps a small 0.95 reserve
 * for the canvas's focused-line emphasis (~1.1x zoom on the active row);
 * normal lines pass through untouched with their authored timing, so focus
 * and auto-scroll follow lyric lines instead of fabricated chunks.
 */
@Composable
private fun KaraokeLineWrapScope(
    lines: List<LyricLine>,
    isOverallRtl: Boolean,
    trackTitle: String,
    trackArtist: String,
    normalStyle: TextStyle,
    accompanimentStyle: TextStyle,
    currentPosition: () -> Int,
    player: MusicPlayer,
    modifier: Modifier = Modifier,
    lyricsOffsetMs: Long = 0L,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val wrapBudgetPx = remember(maxWidth, density) {
            // Tight on purpose: the splitter measures word-by-word while the
            // canvas draws continuous text (kerning can add a pixel or two),
            // and the focused line zooms ~1.1x — 0.95 covers both without
            // chopping lines that fit. Only true overflow splits.
            with(density) { (maxWidth - KaraokeHorizontalChrome).toPx().coerceAtLeast(0f) } * 0.95f
        }
        val displayLines = remember(lines, wrapBudgetPx, normalStyle) {
            // Word-sync rows split on syllable timing, line-sync rows on
            // proportional time slices — either way every drawn row fits.
            val backfilled = backfillLineSyncDurations(lines)
            backfilled.flatMap { line ->
                if (!line.hasSyllables) {
                    line.splitLineSyncToFit(wrapBudgetPx) { text ->
                        textMeasurer.measure(text, normalStyle).size.width.toFloat()
                    }
                } else {
                    line.splitKaraokeToFit(wrapBudgetPx) { text ->
                        textMeasurer.measure(text, normalStyle).size.width.toFloat()
                    }
                }
            }
        }
        val syncedLyrics = remember(displayLines, trackTitle, trackArtist, isOverallRtl) {
            displayLines.toSyncedLyrics(trackTitle, trackArtist, isOverallRtl)
        }
        val initialLineIndex = remember(syncedLyrics) {
            val time = currentPosition()
            val idx = syncedLyrics.lines.indexOfFirst { time in it.start..it.end }
            if (idx != -1) idx else syncedLyrics.lines.indexOfFirst { it.start > time }.takeIf { it != -1 } ?: 0
        }
        // Reset scroll state whenever the lyrics themselves change (new
        // track or provider upgrade); otherwise the previous song's scroll
        // offset leaks into this one until auto-scroll corrects it.
        val listState = key(syncedLyrics) {
            rememberLazyListState(initialFirstVisibleItemIndex = initialLineIndex)
        }
        KaraokeLyricsView(
            listState = listState,
            lyrics = syncedLyrics,
            showTranslation = true,
            showPhonetic = true,
            currentPosition = currentPosition,
            onLineClicked = { line ->
                // Inverse of the highlight shift: tap targets audio time.
                player.seekTo((line.start - lyricsOffsetMs).coerceAtLeast(0).toLong())
            },
            onLinePressed = {},
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            offset = 84.dp,
            normalLineTextStyle = normalStyle,
            accompanimentLineTextStyle = accompanimentStyle,
            textColor = Color.White,
        )
    }
}

private fun LyricLine.toISyncedLine(isOverallRtl: Boolean = false): ISyncedLine {
    val lineStart = timeMs.toInt()
    val lineEnd = if (durationMs > 0) (timeMs + durationMs).toInt()
    else if (syllables.isNotEmpty()) (syllables.last().timeMs + syllables.last().durationMs).toInt()
    else lineStart + 4500  // reasonable fallback; backfilled by toSyncedLyrics

    val isLineRtl = isRtl || (isOverallRtl && (text.isBlank() || text == "♪"))

    return if (hasSyllables) {
        val leadSyllables = syllables.filter { !it.isBackground }.ifEmpty { syllables }
        val bgSyllables = if (leadSyllables.size < syllables.size) syllables.filter { it.isBackground } else emptyList()
        val needsSpacing = text.contains(' ') || text.contains('\u00A0')

        // The row's own end caps every syllable: without the cap a generous
        // provider duration lets one word's fill run into the next word (or
        // the next row), which reads as the highlight jumping or stalling.
        val rowEnd = if (durationMs > 0) (timeMs + durationMs).toInt() else Int.MAX_VALUE

        fun List<LyricSyllable>.toKaraokeSyllables(): List<KaraokeSyllable> {
            // Separator rule lives in renderedSyllableContents (shared with
            // KaraokeLineSplitter's word grouping) so the wrap points the
            // splitter breaks at are exactly the points the canvas can wrap.
            val contents = renderedSyllableContents(this, needsSpacing)
            return mapIndexed { index, syl ->
                val sStart = syl.timeMs.toInt()
                val minDur = if (syl.durationMs > 0) syl.durationMs.toInt() else {
                    val nextSyl = getOrNull(index + 1)
                    if (nextSyl != null && nextSyl.timeMs > syl.timeMs) (nextSyl.timeMs - syl.timeMs).toInt()
                    else 150
                }
                var sEnd = (sStart + minDur).coerceAtLeast(sStart + 50)
                val nextStart = getOrNull(index + 1)?.timeMs?.toInt()
                if (nextStart != null && nextStart > sStart && sEnd > nextStart) sEnd = nextStart
                if (rowEnd > sStart && sEnd > rowEnd) sEnd = rowEnd
                KaraokeSyllable(
                    content = contents[index],
                    start = sStart,
                    end = sEnd.coerceAtLeast(sStart),
                )
            }
        }

        val mainSyllables = leadSyllables.toKaraokeSyllables()
        val effectiveStart = if (mainSyllables.isNotEmpty()) minOf(lineStart, mainSyllables.first().start) else lineStart
        val effectiveEnd = if (mainSyllables.isNotEmpty()) maxOf(lineEnd, mainSyllables.last().end) else lineEnd

        val accompaniment = if (bgSyllables.isNotEmpty()) {
            val bgKaraokeSyllables = bgSyllables.toKaraokeSyllables()
            val bgStart = bgKaraokeSyllables.first().start
            val bgEnd = bgKaraokeSyllables.last().end.coerceAtLeast(bgStart + 50)
            listOf(
                KaraokeLine.AccompanimentKaraokeLine(
                    syllables = bgKaraokeSyllables,
                    translation = null,
                    alignment = if (isLineRtl) KaraokeAlignment.Start else KaraokeAlignment.End,
                    start = bgStart,
                    end = bgEnd,
                    phonetic = null,
                ),
            )
        } else {
            emptyList()
        }

        KaraokeLine.MainKaraokeLine(
            syllables = mainSyllables,
            translation = null,
            phonetic = transliteration,
            alignment = if (isLineRtl) KaraokeAlignment.End else KaraokeAlignment.Start,
            start = effectiveStart,
            end = effectiveEnd.coerceAtLeast(effectiveStart + 100),
            accompanimentLines = accompaniment,
        )
    } else {
        SyncedLine(
            start = lineStart,
            end = lineEnd.coerceAtLeast(lineStart + 100),
            content = text,
            translation = transliteration,
        )
    }
}

private fun List<LyricLine>.toSyncedLyrics(title: String, artist: String, isOverallRtl: Boolean = false): SyncedLyrics {
    // End times are backfilled first (shared helper, idempotent), so every
    // row — word-sync or line-sync — maps onto one contiguous clock.
    val backfilled = backfillLineSyncDurations(this)
    return SyncedLyrics(
        lines = backfilled.map { it.toISyncedLine(isOverallRtl) },
        title = title,
        artists = listOf(Artist(type = "artist", name = artist)),
    )
}

@Composable
private fun ModernPlainLyricsView(
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
                    fontSize = 28.sp,
                    lineHeight = 46.sp,
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
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }
        }
    }
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
    modifier: Modifier = Modifier,
) {
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
                IconButton(
                    onClick = onToggleFullscreen,
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
