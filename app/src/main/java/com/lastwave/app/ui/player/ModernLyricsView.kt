package com.lastwave.app.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
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
) {
    val track = state.current ?: return

    val progress by (progressState ?: player.progressState).collectAsStateWithLifecycle(
        initialValue = PlaybackProgressState(positionMs = state.positionMs, durationMs = state.durationMs),
    )

    // Keyed on the whole track: videoId is null for local/search tracks,
    // and a null key would leak the previous song's smoothing state.
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
                        // Word-sync can fail (all providers down / LRCLIB line
                        // fallback): huge karaoke type then overflows off-screen.
                        // Fall back to a smaller line style, and sit the list a
                        // little lower so the first line clears the header.
                        val isWordSynced = targetState.isWordSynced ||
                            remember(targetState.lines) { targetState.lines.any { it.hasSyllables } }
                        val isOverallRtl = remember(targetState.lines) {
                            val meaningful = targetState.lines.filter { it.text.isNotBlank() && it.text != "♪" }
                            if (meaningful.isEmpty()) false
                            else meaningful.count { it.isRtl } > meaningful.size / 2
                        }
                        val syncedLyrics = remember(targetState.lines, track.title, track.artist, isOverallRtl) {
                            targetState.lines.toSyncedLyrics(track.title, track.artist, isOverallRtl)
                        }

                        val initialLineIndex = remember(syncedLyrics) {
                            val time = smoothedPositionMs.toInt()
                            val idx = syncedLyrics.lines.indexOfFirst { time in it.start..it.end }
                            if (idx != -1) idx else syncedLyrics.lines.indexOfFirst { it.start > time }.takeIf { it != -1 } ?: 0
                        }
                        // Reset scroll state whenever the lyrics themselves
                        // change (new track or provider upgrade); otherwise
                        // the previous song's scroll offset leaks into this
                        // one until auto-scroll corrects it.
                        val listState = key(syncedLyrics) {
                            rememberLazyListState(initialFirstVisibleItemIndex = initialLineIndex)
                        }

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
                                KaraokeLyricsView(
                                    listState = listState,
                                    lyrics = syncedLyrics,
                                    showTranslation = true,
                                    showPhonetic = true,
                                    currentPosition = { smoothedPositionMs.toInt() },
                                    onLineClicked = { line ->
                                        player.seekTo(line.start.toLong())
                                    },
                                    onLinePressed = {},
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                    offset = 84.dp,
                                    normalLineTextStyle = LocalTextStyle.current.copy(
                                        fontSize = if (isWordSynced) 28.sp else 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        textMotion = TextMotion.Animated,
                                    ),
                                    accompanimentLineTextStyle = LocalTextStyle.current.copy(
                                        fontSize = if (isWordSynced) 20.sp else 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        textMotion = TextMotion.Animated,
                                    ),
                                    textColor = Color.White,
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

        fun List<LyricSyllable>.toKaraokeSyllables(): List<KaraokeSyllable> {
            return mapIndexed { index, syl ->
                val sStart = syl.timeMs.toInt()
                val minDur = if (syl.durationMs > 0) syl.durationMs.toInt() else {
                    val nextSyl = getOrNull(index + 1)
                    if (nextSyl != null && nextSyl.timeMs > syl.timeMs) (nextSyl.timeMs - syl.timeMs).toInt()
                    else 150
                }
                val sEnd = (sStart + minDur).coerceAtLeast(sStart + 50)
                val next = getOrNull(index + 1)
                val separator = if (needsSpacing &&
                    index < lastIndex &&
                    !syl.text.endsWith(' ') &&
                    !syl.text.endsWith('\u00A0') &&
                    next?.appendToPrevious != true &&
                    (next == null || (!next.text.startsWith(' ') && !next.text.startsWith('\u00A0')))
                ) " " else ""
                KaraokeSyllable(
                    content = syl.text + separator,
                    start = sStart,
                    end = sEnd,
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
    // Backfill end times: for lines without explicit duration and no syllables,
    // set duration to reach the next line's start (eliminates gaps/overlaps).
    val backfilled = mapIndexed { i, line ->
        if (line.durationMs <= 0 && line.syllables.isEmpty() && i < lastIndex) {
            val nextStart = this[i + 1].timeMs
            if (nextStart > line.timeMs) {
                val gap = nextStart - line.timeMs
                val dur = if (gap <= 6000L) gap else 4500L
                line.copy(durationMs = dur)
            } else line
        } else if (line.durationMs <= 0 && line.syllables.isEmpty() && i == lastIndex) {
            line.copy(durationMs = 4500L)
        } else line
    }
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
