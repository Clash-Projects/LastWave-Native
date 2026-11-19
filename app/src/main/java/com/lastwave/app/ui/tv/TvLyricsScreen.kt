package com.lastwave.app.ui.tv

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.ui.player.LyricsUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV lyrics tab, BitChord-style: the current line is large and brand-colored
 * while the rest stays quiet, and the list auto-scrolls with playback. Lines
 * are deliberately not focusable — the remote keeps the rail, BACK returns
 * Home, and the words simply follow the song.
 */
@Composable
fun TvLyricsTab(
    viewModel: TvHomeViewModel,
    railHomeFocus: androidx.compose.ui.focus.FocusRequester,
) {
    val lyrics = viewModel.lyricsState
    val chrome by viewModel.musicPlayer.chromeState.collectAsStateWithLifecycle()
    val track = chrome.current

    // Tab-entry focus: the synced list focuses itself once lyrics exist; in
    // every other state the rail takes focus so the remote is never dead.
    LaunchedEffect(lyrics) {
        withFrameNanos { }
        if (lyrics !is LyricsUiState.Success) {
            runCatching { railHomeFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        when (lyrics) {
            LyricsUiState.Idle, is LyricsUiState.Error, LyricsUiState.Empty ->
                TvLyricsPrompt(
                    headline = when (lyrics) {
                        is LyricsUiState.Error -> (lyrics as LyricsUiState.Error).message
                        LyricsUiState.Empty -> stringResource(R.string.tv_lyrics_not_found)
                        else -> stringResource(R.string.tv_lyrics_empty)
                    },
                    showRetry = lyrics is LyricsUiState.Error,
                    onRetry = viewModel::reloadLyrics,
                )
            LyricsUiState.Loading -> Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = stringResource(R.string.tv_lyrics_loading),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is LyricsUiState.Success -> {
                val success = lyrics as LyricsUiState.Success
                if (track != null) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sourceLabel = success.source
                    val subtitle = if (sourceLabel.isNullOrBlank()) {
                        track.artist
                    } else {
                        stringResource(R.string.tv_lyrics_source, sourceLabel)
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                if (success.isSynced) {
                    Text(
                        text = stringResource(R.string.tv_lyrics_scroll_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TvSyncedLyrics(lines = success.lines, progressState = viewModel.musicPlayer.progressState)
                } else {
                    val plain = success.plainLyrics?.takeIf { it.isNotBlank() }
                        ?: success.lines.joinToString(separator = "\n") { it.text }
                    Text(
                        text = plain,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSyncedLyrics(
    lines: List<LyricLine>,
    progressState: kotlinx.coroutines.flow.StateFlow<com.lastwave.app.playback.PlaybackProgressState>,
) {
    val listState = rememberLazyListState()
    val progress by progressState.collectAsStateWithLifecycle()
    val listFocus = remember { FocusRequester() }

    // The lyrics list is the pane's single D-pad target: grab focus on entry.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { listFocus.requestFocus() }
    }
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // The in-flight manual scroll. Key repeats fire roughly ten events a
    // second while a direction is held, so each new event cancels the still-
    // running animation instead of queueing behind it — otherwise the mutex
    // serialises every launch and the list keeps gliding for seconds after
    // the key is released.
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    fun cancelManualScroll() {
        scrollJob?.cancel()
        scrollJob = null
    }

    // Deriving the active index from the position flow keeps per-tick work to a
    // single int comparison — recomposition only happens when the line changes.
    val activeLine by remember(lines) {
        derivedStateOf {
            val positionMs = progress.positionMs
            var index = -1
            for (i in lines.indices) {
                if (lines[i].timeMs <= positionMs) index = i else break
            }
            index
        }
    }

    // The list itself is the D-pad target: UP/DOWN scroll manually, and the
    // auto-follow pauses for a few seconds after the user takes over.
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(listFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionDown -> 1f
                    Key.DirectionUp -> -1f
                    else -> return@onPreviewKeyEvent false
                }
                lastUserScrollAt = SystemClock.uptimeMillis()
                cancelManualScroll()
                scrollJob = scope.launch {
                    val step = with(density) { LYRICS_SCROLL_STEP_DP.dp.toPx() }
                    listState.animateScrollBy(direction * step)
                }
                true
            },
        contentPadding = PaddingValues(top = 140.dp, bottom = 420.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "$index-${line.timeMs}" }) { index, line ->
            TvLyricLineRow(line = line, active = index == activeLine)
        }
    }

    LaunchedEffect(activeLine) {
        if (activeLine >= 0 && SystemClock.uptimeMillis() - lastUserScrollAt > USER_SCROLL_HOLD_MS) {
            runCatching { listState.animateScrollToItem(activeLine) }
        }
    }
}

private const val USER_SCROLL_HOLD_MS = 4_000L
private const val LYRICS_SCROLL_STEP_DP = 96

@Composable
private fun TvLyricLineRow(line: LyricLine, active: Boolean) {
    // Colour and emphasis glide between lines instead of snapping.
    val lineColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 260),
        label = "tvLyricLineColor",
    )
    val lineAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.72f,
        animationSpec = tween(durationMillis = 260),
        label = "tvLyricLineAlpha",
    )
    Text(
        text = line.text,
        style = if (active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = lineColor,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = lineAlpha },
    )
}

@Composable
private fun TvLyricsPrompt(headline: String, showRetry: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (showRetry) {
            Spacer(modifier = Modifier.height(20.dp))
            val interaction = remember { MutableInteractionSource() }
            var focused by remember { mutableStateOf(false) }
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(showRetry) {
                withFrameNanos { }
                runCatching { retryFocus.requestFocus() }
            }
            Text(
                text = stringResource(R.string.tv_lyrics_retry),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .focusRequester(retryFocus)
                    .tvLift(focused = focused, shape = RoundedCornerShape(14.dp), focusedScale = 1.05f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(interactionSource = interaction, indication = null, onClick = onRetry)
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}
