package com.lastwave.app.ui.genres

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.GenreStat
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.expressivePressScale
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenresScreen(
    onBack: () -> Unit = {},
    onNavigateToPlaylist: () -> Unit = {},
    viewModel: GenresViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ranked = remember(state.stats) { state.stats.sortedByDescending { it.count } }

    LaunchedEffect(state.navigateToPlaylist) {
        if (state.navigateToPlaylist) {
            onNavigateToPlaylist()
            viewModel.consumeNavigateToPlaylist()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.storage_back))
                }
                Text(
                    stringResource(R.string.ge_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PeriodDropdown(state.period, state.isLoading && ranked.isNotEmpty(), viewModel::setPeriod)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading && ranked.isEmpty() -> Box(
                        Modifier.fillMaxSize().padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) { com.lastwave.app.ui.common.ExpressiveLoadingIndicator() }
                    ranked.isEmpty() -> Box(
                        Modifier.fillMaxSize().padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.ge_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(if (maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f) 1 else 2),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 8.dp,
                            bottom = 16.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "intro", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.ge_glance),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                        item(key = "spectrum", span = { GridItemSpan(maxLineSpan) }) {
                            GenreSpectrumCard(ranked, enabled = !state.isLoading) {
                                viewModel.openDetail(ranked.first().name)
                            }
                        }
                        if (ranked.size > 1) item(key = "remaining", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.ge_beyond_top),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
                            )
                        }
                        gridItemsIndexed(ranked.drop(1), key = { _, stat -> "genre:" + stat.name }) { index, stat ->
                            GenreRankItem(
                                stat = stat,
                                rank = index + 2,
                                enabled = !state.isLoading,
                                onClick = { viewModel.openDetail(stat.name) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = ExpressiveMotion.effectsDefault(),
                                    placementSpec = ExpressiveMotion.spatialDefault(),
                                    fadeOutSpec = ExpressiveMotion.effectsFast(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    state.detailGenre?.let { genre ->
        GenreDetailSheet(
            genre = genre,
            state = state,
            onDismiss = viewModel::closeDetail,
            onLoadMore = { viewModel.loadDetailPage() },
            onStartMix = { viewModel.startMix(genre) },
            onDiscoverMore = { viewModel.discoverMore(genre) },
            onShowYourTracks = viewModel::showYourTracks,
            onExploreGenre = { viewModel.exploreGenre(it) },
        )
    }
}

@Composable
private fun PeriodDropdown(period: String, isRefreshing: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = GENRE_PERIODS.firstOrNull { it.first == period }?.second ?: "Overall"
    val updatingLabel = stringResource(R.string.ge_updating)
    val interactionSource = remember { MutableInteractionSource() }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            interactionSource = interactionSource,
            modifier = Modifier
                .widthIn(max = 124.dp)
                .expressivePressScale(interactionSource, targetScale = 0.98f)
                .semantics { if (isRefreshing) stateDescription = updatingLabel },
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (isRefreshing) {
                    com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator(size = 16.dp)
                } else {
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GENRE_PERIODS.forEach { (value, text) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(text) }, onClick = { onChange(value); expanded = false })
            }
        }
    }
}

@Composable
private fun GenreSpectrumCard(stats: List<GenreStat>, enabled: Boolean, onClick: () -> Unit) {
    val leader = stats.first()
    val visible = stats.take(18)
    val interactionSource = remember { MutableInteractionSource() }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    // Stable rank slots let a new period retarget the current bars instead of rebuilding them.
    val heights = List(18) { index ->
        animateFloatAsState(
            targetValue = if (revealed) visible.getOrNull(index)?.percentOfTop ?: 0f else 0f,
            animationSpec = ExpressiveMotion.effectsSlow(),
            label = "genreSpectrumHeight" + index,
        )
    }
    val cellWidth = animateFloatAsState(
        targetValue = 1f / visible.size,
        animationSpec = ExpressiveMotion.spatialDefault(),
        label = "genreSpectrumSpacing",
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().expressivePressScale(interactionSource, targetScale = 0.98f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ge_leading_genre), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(leader.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                    Text(stringResource(R.string.ge_strength_percent, (leader.percentOfTop * 100).toInt()),
                        style = MaterialTheme.typography.titleLarge)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics {}) {
                clipRect {
                    val cell = size.width * cellWidth.value
                    val width = (cell - 6.dp.toPx()).coerceAtLeast(0f)
                    heights.forEachIndexed { index, value ->
                        val height = size.height * value.value.coerceIn(0f, 1f)
                        if (width > 0f && height > 0f) {
                            val left = index * cell + (cell - width) / 2
                            drawRoundRect(
                                color = if (index == 0) primary else secondary,
                                topLeft = Offset(
                                    if (layoutDirection == LayoutDirection.Rtl) size.width - left - width else left,
                                    size.height - height,
                                ),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(minOf(width, height) / 2),
                            )
                        }
                    }
                }
            }
            Text(stringResource(R.string.ge_relative_strength), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GenreRankItem(
    stat: GenreStat,
    rank: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val strength = animateFloatAsState(
        targetValue = if (revealed) stat.percentOfTop else 0f,
        animationSpec = ExpressiveMotion.effectsDefault(),
        label = "genreRankStrength",
    )
    val primary = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        color = Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().expressivePressScale(interactionSource, targetScale = 0.98f),
    ) {
        Column(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rank.toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stat.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ge_strength_percent, (stat.percentOfTop * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().padding(start = 24.dp).height(3.dp).clearAndSetSemantics {}) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                val width = size.width * strength.value.coerceIn(0f, 1f)
                if (width > 0f) {
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f),
                        size = Size(width, size.height),
                        cornerRadius = CornerRadius(minOf(width, size.height) / 2),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreDetailSheet(
    genre: String,
    state: GenresUiState,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onStartMix: () -> Unit,
    onDiscoverMore: () -> Unit,
    onShowYourTracks: () -> Unit,
    onExploreGenre: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var menuTrack by remember { mutableStateOf<GeneratedTrack?>(null) }
    val listState = rememberLazyListState()

    val shouldLoadMore by remember(listState, state.detailTracks.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.detailTracks.size - 5
        }
    }
    LaunchedEffect(shouldLoadMore, state.detailTracks.size, state.detailLoading, state.detailHasMore, state.isDiscoverMode) {
        if (shouldLoadMore && !state.detailLoading && state.detailHasMore && !state.isDiscoverMode) onLoadMore()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 640.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(genre.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (state.isDiscoverMode) "Discoveries \u2022 Fresh recommendations" else "Your Tracks \u2022 Based on your scrobbles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Button(onClick = onStartMix, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.fr_start_mix))
                }
                if (state.isDiscoverMode) {
                    androidx.compose.material3.OutlinedButton(onClick = onShowYourTracks, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ge_tracks))
                    }
                } else {
                    androidx.compose.material3.OutlinedButton(onClick = onDiscoverMore, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ge_discover))
                    }
                }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(bottom = 12.dp)) {
                itemsIndexed(state.detailTracks, key = { index, track -> "${track.key}_$index" }) { _, track ->
                    GenreTrackRow(
                        track = track,
                        allTracks = state.detailTracks,
                        genreTitle = "${genre.replaceFirstChar { it.uppercase() }}",
                        onMenu = { menuTrack = track },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (state.detailLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator()
                        }
                    }
                }
                if (!state.detailLoading && state.detailTracks.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.ge_no_tracks), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    menuTrack?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = "Genres",
            onDismiss = { menuTrack = null },
            onExploreGenre = onExploreGenre,
        )
    }
}

@Composable
private fun GenreTrackRow(
    track: GeneratedTrack,
    allTracks: List<GeneratedTrack>,
    genreTitle: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val queue = allTracks.map {
                    com.lastwave.app.playback.PlayableTrack(it.name, it.artist, album = it.album, artworkUrl = it.artworkUrl)
                }
                val index = allTracks.indexOf(track).coerceAtLeast(0)
                musicPlayer.playQueue(queue, startIndex = index, sourceLabel = genreTitle)
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(name = track.name, artist = track.artist, embeddedUrl = track.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        com.lastwave.app.ui.common.OverflowMenuButton(onClick = onMenu)
    }
}
