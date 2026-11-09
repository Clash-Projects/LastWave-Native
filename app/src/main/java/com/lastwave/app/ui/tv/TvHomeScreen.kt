package com.lastwave.app.ui.tv

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.lastwave.app.R
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.theme.ArtworkShape
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Leanback (Android TV) shell: a left icon rail (Home / Search) plus the
 * focused pane. Replaces the touch NavHost entirely on TV — the phone
 * navigation stack assumes touch input and cannot be driven from the D-pad.
 * All playback goes through the shared [com.lastwave.app.playback.MusicPlayer]
 * singleton, so the media session, queue and radio behave exactly as on phone.
 */
@Composable
fun TvHomeRoot(viewModel: TvHomeViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableStateOf(TvTab.HOME) }
    val chrome by viewModel.musicPlayer.chromeState.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize()) {
            TvNavRail(
                selectedTab = tab,
                onSelect = { tab = it },
                isPlaying = chrome.isPlaying,
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    TvTab.HOME -> TvHomeTab(viewModel)
                    TvTab.SEARCH -> TvSearchTab(viewModel)
                }
            }
        }
    }
}

private enum class TvTab { HOME, SEARCH }

/** Brand mark: lime disc with the four-bar equalizer, drawn in place (no bitmap). */
@Composable
private fun TvLogoBadge(modifier: Modifier = Modifier) {
    val discColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val disc = size.minDimension
        drawCircle(color = discColor, radius = disc / 2f)
        // Logo proportions: bar heights follow the 4-bar wave mark, centred vertically.
        val heights = floatArrayOf(0.50f, 0.68f, 0.68f, 0.55f)
        val barWidth = disc * 0.105f
        val gap = disc * 0.082f
        val startX = (disc - (heights.size * barWidth + (heights.size - 1) * gap)) / 2f
        heights.forEachIndexed { index, barHeight ->
            val barHeightPx = disc * barHeight
            drawRoundRect(
                color = Color(0xFF001628),
                topLeft = Offset(startX + index * (barWidth + gap), (disc - barHeightPx) / 2f),
                size = Size(barWidth, barHeightPx),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun TvNavRail(
    selectedTab: TvTab,
    onSelect: (TvTab) -> Unit,
    isPlaying: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(112.dp)
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TvLogoBadge(modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.weight(1f))
        TvRailItem(
            icon = Icons.Rounded.Home,
            label = stringResource(R.string.tv_tab_home),
            selected = selectedTab == TvTab.HOME,
            onClick = { onSelect(TvTab.HOME) },
        )
        TvRailItem(
            icon = Icons.Rounded.Search,
            label = stringResource(R.string.tv_tab_search),
            selected = selectedTab == TvTab.SEARCH,
            onClick = { onSelect(TvTab.SEARCH) },
        )
        Spacer(modifier = Modifier.weight(1f))
        if (isPlaying) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp).alpha(0.9f),
            )
        }
    }
}

/** Rail entry; clickable() alone provides the D-pad focus target. */
@Composable
private fun TvRailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        isFocused -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> Color.Transparent
                    }
                )
                .border(
                    width = 2.dp,
                    color = if (isFocused && !selected) MaterialTheme.colorScheme.outline else Color.Transparent,
                    shape = CircleShape,
                )
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TvHomeTab(viewModel: TvHomeViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "greeting") { TvGreeting() }
        item(key = "hero") { TvNowPlayingHero(viewModel = viewModel) }
        if (viewModel.topTracks.isNotEmpty()) {
            item(key = "top-header") { TvSectionTitle(stringResource(R.string.tv_top_tracks)) }
            item(key = "top-row") {
                TvHistoryRow(rows = viewModel.topTracks, onPlay = viewModel::playHistoryRow)
            }
        }
        if (viewModel.recentTracks.isNotEmpty()) {
            item(key = "recent-header") { TvSectionTitle(stringResource(R.string.tv_recently_played)) }
            item(key = "recent-row") {
                TvHistoryRow(rows = viewModel.recentTracks, onPlay = viewModel::playHistoryRow)
            }
        }
        if (viewModel.topTracks.isEmpty() && viewModel.recentTracks.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.tv_empty_history),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TvGreeting() {
    val hour = LocalTime.now().hour
    val greeting = when (hour) {
        in 5..11 -> stringResource(R.string.tv_greeting_morning)
        in 12..16 -> stringResource(R.string.tv_greeting_afternoon)
        in 17..21 -> stringResource(R.string.tv_greeting_evening)
        else -> stringResource(R.string.tv_greeting_night)
    }
    Column {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val formatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
        Text(
            text = remember { LocalDate.now().format(formatter) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TvSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Now-playing hero: the screen's single focal point — art, words, transport. */
@Composable
private fun TvNowPlayingHero(viewModel: TvHomeViewModel) {
    val chrome by viewModel.musicPlayer.chromeState.collectAsStateWithLifecycle()
    val progress by viewModel.musicPlayer.progressState.collectAsStateWithLifecycle()
    val shuffle by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val seekBarFocus = remember { FocusRequester() }
    var seekBarFocused by remember { mutableStateOf(false) }
    val track: PlayableTrack? = chrome.current

    LaunchedEffect(track != null) {
        if (track != null) playFocus.requestFocus()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        TvArtwork(artworkUrl = track?.artworkUrl, size = 220.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (track == null) {
                Text(
                    text = stringResource(R.string.tv_nothing_playing),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.tv_nothing_playing_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                // D-pad scrub row: focus it and press LEFT/RIGHT for ±10 s.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .focusRequester(seekBarFocus)
                        .focusable()
                        .onFocusChanged { seekBarFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val delta = when (event.key) {
                                Key.DirectionRight -> 10_000L
                                Key.DirectionLeft -> -10_000L
                                else -> return@onPreviewKeyEvent false
                            }
                            viewModel.seekTo(
                                (progress.positionMs + delta).coerceIn(0L, progress.durationMs),
                            )
                            true
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val fraction = if (progress.durationMs > 0) {
                        progress.positionMs.toFloat() / progress.durationMs
                    } else 0f
                    val barHeight = if (seekBarFocused) 8.dp else 4.dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(barHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = DateUtils.formatElapsedTime(progress.positionMs / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = DateUtils.formatElapsedTime(progress.durationMs / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvTransportButton(
                        icon = if (shuffle) Icons.Rounded.ShuffleOn else Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.tv_shuffle),
                        active = shuffle,
                        onClick = viewModel::toggleShuffle,
                    )
                    TvTransportButton(
                        icon = Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.tv_previous),
                        onClick = viewModel::previous,
                    )
                    TvTransportButton(
                        icon = if (chrome.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (chrome.isPlaying) {
                            stringResource(R.string.tv_pause)
                        } else {
                            stringResource(R.string.tv_play)
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        iconTint = MaterialTheme.colorScheme.onPrimary,
                        iconSize = 40.dp,
                        buttonSize = 72.dp,
                        modifier = Modifier.focusRequester(playFocus),
                        onClick = viewModel::togglePlayPause,
                    )
                    TvTransportButton(
                        icon = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.tv_next),
                        onClick = viewModel::next,
                    )
                    TvTransportButton(
                        icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = stringResource(R.string.tv_repeat),
                        active = repeatMode != Player.REPEAT_MODE_OFF,
                        onClick = viewModel::cycleRepeatMode,
                    )
                }
            }
        }
    }
}

private val TransportShape = CircleShape

@Composable
private fun TvTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    containerColor: Color = Color.Transparent,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    iconSize: Dp = 28.dp,
    buttonSize: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1f,
        animationSpec = ExpressiveMotion.spatialFast(),
        label = "tvTransportScale",
    )
    Box(
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(TransportShape)
            .background(
                when {
                    isFocused && containerColor == Color.Transparent -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> containerColor
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused && containerColor == Color.Transparent) {
                MaterialTheme.colorScheme.onSurface
            } else {
                iconTint
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Horizontal history shelf: top tracks / recently played. */
@Composable
private fun TvHistoryRow(rows: List<SongPlayStatsEntity>, onPlay: (SongPlayStatsEntity) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(rows, key = { index, row -> "$index-${row.trackKey}" }) { _, row ->
            TvTrackCard(
                title = row.title,
                artist = row.artist,
                artworkUrl = row.artworkUrl,
                onClick = { onPlay(row) },
            )
        }
    }
}

@Composable
private fun TvTrackCard(
    title: String,
    artist: String,
    artworkUrl: String?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = ExpressiveMotion.spatialFast(),
        label = "tvCardScale",
    )
    Column(
        modifier = Modifier
            .width(184.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(184.dp)
                .clip(ArtworkShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = ArtworkShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvArtwork(artworkUrl: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(ArtworkShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

/** Remote-keyboard search: results land as a vertical list under the field. */
@Composable
private fun TvSearchTab(viewModel: TvHomeViewModel) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        fieldFocus.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_search_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus),
            placeholder = { Text(stringResource(R.string.tv_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
        when {
            viewModel.isSearching -> Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            viewModel.searchResults.isEmpty() && query.isNotBlank() -> Text(
                text = stringResource(R.string.tv_search_no_results),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(viewModel.searchResults, key = { index, track -> "$index-${track.videoId}" }) { _, track ->
                    TvSearchResultRow(track = track, onPlay = { viewModel.playTrack(track) })
                }
            }
        }
    }
}

@Composable
private fun TvSearchResultRow(track: YouTubeMusicTrack, onPlay: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvArtwork(artworkUrl = track.artworkUrl, size = 56.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.tv_play),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
    }
}
