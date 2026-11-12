package com.lastwave.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.lastwave.app.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.lastwave.app.playback.toPlayableTrack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.style.TextOverflow
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.TrackMiniTrayData
import com.lastwave.app.ui.common.TrackMiniTraySheet
import com.lastwave.app.ui.theme.ArtworkShape
import com.lastwave.app.ui.theme.BadgePillShape
import com.lastwave.app.ui.theme.ExpressiveHeroShape
import com.lastwave.app.ui.theme.ExpressivePillShape
import com.lastwave.app.ui.theme.HeroInnerShape
import com.lastwave.app.ui.theme.ListContainerShape
import com.lastwave.app.ui.theme.NowPlayingCardShape
import com.lastwave.app.ui.theme.StatPillShape
import com.lastwave.app.ui.theme.TrackRowShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically

import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import com.lastwave.app.ui.shell.FloatingNavDefaults
import coil.compose.SubcomposeAsyncImage
import com.lastwave.app.data.repository.HomeAlbum
import com.lastwave.app.data.repository.HomeArtistItem
import com.lastwave.app.data.repository.HomeSortMode
import com.lastwave.app.data.repository.HomeTrack
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Immutable shapes hoisted out of composition: previously each of these was
// constructed inline inside row/card composables, i.e. re-allocated for every
// row on every recomposition. Rows are the hottest path while scrolling, so
// they must not allocate.

internal val ListContainerShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
internal val TrackRowShape = RoundedCornerShape(18.dp)
internal val NowPlayingCardShape = RoundedCornerShape(22.dp)
internal val ArtworkShape = RoundedCornerShape(14.dp)
internal val BadgePillShape = RoundedCornerShape(50)
internal val StatPillShape = RoundedCornerShape(20.dp)
internal val HeroInnerShape = RoundedCornerShape(24.dp)

/**
 * Faithful port of home.html/home.js's layout, top to bottom:
 *  1. Header row — username pill (left) + live listen timer (right)
 *  2. Stats card — big "Scrobbles" number + arrow-to-Genres, then a
 *     Tracks / Artists / Albums row
 *  3. Mix card — "List" title + sort dropdown (Recent / Most Played /
 *     Last 7 Days / Last 30 Days), then the track list itself, with the
 *     Now Playing row always pinned first when present.
 * There's no separate "Now Playing card" — that was an earlier, simplified
 * substitute; the real app renders it as the first row of the same list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenGenres: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    artistAlbumNavigator: com.lastwave.app.ui.navigation.ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeHome>().navigator,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val jamViewModel: com.lastwave.app.ui.jam.LastWaveJamViewModel = hiltViewModel()
    var showJamSheet by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pollWhileActive()
        }
    }

    var menuTrack by remember { mutableStateOf<HomeTrack?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ExpressiveHeader(
                    title = "Statistics",
                    modifier = Modifier.adaptiveContentWidth(maxWidth = 860.dp),
                    actions = {
                        HeaderActionIcon(Icons.Filled.Download, "Downloads", onOpenDownloads)
                        HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                        IconButton(onClick = onOpenSettings) {
                            ProfileAvatar(avatarUrl = uiState.stats?.avatarUrl, modifier = Modifier.size(38.dp))
                        }
                    },
                )
            }
        },
    ) { scaffoldPadding ->
        if (uiState.isLoading) {
            Box(
                Modifier.fillMaxSize().padding(scaffoldPadding).safeHorizontalContentPadding(),
                contentAlignment = Alignment.Center,
            ) {
                com.lastwave.app.ui.common.ExpressiveLoadingIndicator(message = "Loading your listening history")
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .adaptiveContentWidth(maxWidth = 860.dp)
                    .safeHorizontalContentPadding(),
            ) {
            HeaderRow(
                displayUsername = when {
                    uiState.isViewingFriend -> uiState.viewingUsername
                    uiState.username.isNotBlank() -> uiState.username
                    uiState.isLocalStatsMode -> "Guest"
                    else -> uiState.username
                },
                isViewingFriend = uiState.isViewingFriend,
                onClick = onOpenFriends,
                viewModel = viewModel,
            )
            Spacer(Modifier.height(2.dp))

            if (uiState.isLocalStatsMode && !uiState.isViewingFriend) {
                LocalStatsBanner(
                    isOfflineFallback = uiState.isLastFmConnected,
                    onClick = if (uiState.isLastFmConnected) viewModel::refresh else onOpenSettings,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                )
            }

            uiState.stats?.let { stats ->
                StatsCard(
                    scrobbles = stats.scrobbles,
                    trackCount = stats.trackCount,
                    artistCount = stats.artistCount,
                    albumCount = stats.albumCount,
                    // Honest label: local Room aggregates are plays, not global scrobbles.
                    headlineLabel = if (uiState.isLocalStatsMode && !uiState.isViewingFriend) "Plays" else "Scrobbles",
                    onOpenGenres = onOpenGenres,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 0.dp),
                )
                Spacer(Modifier.height(12.dp))
            }


            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, uiState.allTracks.size) {
                    snapshotFlowNearEnd(listState) { viewModel.loadNextPage() }
                }

                // Computed off the main thread in HomeViewModel — see its
                // doc comment on `rows` for why this used to jank on every
                // Last.fm poll tick when it ran inline here instead.
                val rows by viewModel.rows.collectAsStateWithLifecycle()
                val playbackQueue = remember(rows) {
                    rows.mapNotNull { row ->
                        (row as? HomeRow.Track)?.track?.let { track ->
                            com.lastwave.app.playback.PlayableTrack(
                                title = track.name,
                                artist = track.artist,
                                artworkUrl = track.artworkUrl,
                                videoId = track.videoId,
                            )
                        }
                    }
                }
                val playbackIndexByRow = remember(rows) {
                    var nextPlaybackIndex = 0
                    IntArray(rows.size) { rowIndex ->
                        if (rows[rowIndex] is HomeRow.Track) nextPlaybackIndex++ else -1
                    }
                }
                // Content keys without the row index. Suffixing the index made
                // every key shift by one whenever a scrobble or now-playing row
                // landed on top, so the list threw away and rebuilt every
                // visible row (covers flashed, scroll jumped). Duplicates (the
                // same track scrobbled twice in a second) get an occurrence
                // counter instead, which stays stable as rows are prepended.
                val rowKeys = remember(rows) {
                    val seen = HashMap<String, Int>(rows.size)
                    Array(rows.size) { i ->
                        val base = when (val row = rows[i]) {
                            is HomeRow.DateHeader -> "date_${row.label}"
                            is HomeRow.Track -> if (row.track.isNowPlaying) {
                                "nowplaying_${row.track.key}"
                            } else {
                                "track_${row.track.key}_${row.track.timestampMillis}"
                            }
                            is HomeRow.Album -> "album_${row.album.artist}_${row.album.name}"
                            is HomeRow.Artist -> "artist_${row.artist.name}"
                        }
                        val n = seen[base] ?: 0
                        seen[base] = n + 1
                        if (n == 0) base else "${base}#$n"
                    }
                }
                val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
                // Long-press (deep press) on a track row opens the mini tray;
                // the 3-dot button keeps opening the full menu sheet.
                var miniTrayRowIndex by remember { mutableStateOf<Int?>(null) }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .clip(ListContainerShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                        MixHeader(sortMode = uiState.sortMode, onSortModeChange = viewModel::setSortMode)
                    }

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 0.dp,
                            bottom = FloatingNavDefaults.contentBottomPadding(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(
                            rows,
                            key = { index, _ -> rowKeys.getOrNull(index) ?: index.toString() },
                            contentType = { _, row ->
                                when (row) {
                                    is HomeRow.DateHeader -> "date"
                                    is HomeRow.Track -> "track"
                                    is HomeRow.Album -> "album"
                                    is HomeRow.Artist -> "artist"
                                }
                            },
                        ) { rowIndex, row ->
                            Box {
                                when (row) {
                                    is HomeRow.DateHeader -> DateHeaderRow(row.label)
                                    is HomeRow.Track -> TrackRow(
                                        track = row.track,
                                        badge = row.badge,
                                        onClick = {
                                            musicPlayer.playQueue(
                                                tracks = playbackQueue,
                                                startIndex = playbackIndexByRow.getOrElse(rowIndex) { 0 },
                                                sourceLabel = "Home",
                                            )
                                        },
                                        onLongClick = { miniTrayRowIndex = rowIndex },
                                        onMenuClick = { menuTrack = row.track },
                                    )
                                    is HomeRow.Album -> AlbumRow(
                                        album = row.album,
                                        badge = row.badge,
                                        onClick = {
                                            artistAlbumNavigator.openAlbum(
                                                title = row.album.name,
                                                artist = row.album.artist,
                                                browseId = "", // The room DB doesn't have browseId for HomeAlbum easily accessible here unless we added it, but HomeAlbum has no browseId field. It will search.
                                            )
                                        }
                                    )
                                    is HomeRow.Artist -> ArtistRow(
                                        artist = row.artist,
                                        badge = row.badge,
                                        onClick = {
                                            artistAlbumNavigator.openArtist(
                                                name = row.artist.name,
                                                browseId = "", // Will search by name
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        if (rows.isEmpty()) {
                            item(key = "empty", contentType = "empty") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(stringResource(R.string.home_no_tracks), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    miniTrayRowIndex?.let { trayIndex ->
                        (rows.getOrNull(trayIndex) as? HomeRow.Track)?.let { trayRow ->
                            TrackMiniTraySheet(
                                data = TrackMiniTrayData(
                                    title = trayRow.track.name,
                                    artist = trayRow.track.artist,
                                    artworkUrl = trayRow.track.artworkUrl,
                                    sourceLabel = "Home",
                                    onPlay = {
                                        musicPlayer.playQueue(
                                            tracks = playbackQueue,
                                            startIndex = playbackIndexByRow.getOrElse(trayIndex) { 0 },
                                            sourceLabel = "Home",
                                        )
                                    },
                                ),
                                onDismiss = { miniTrayRowIndex = null },
                            )
                        }
                    }
                }
            }
        }
    }
    }

    menuTrack?.let { track ->
        com.lastwave.app.ui.common.TrackContextMenuSheet(
            target = com.lastwave.app.ui.common.TrackMenuTarget.Track(track.name, track.artist, track.artworkUrl.orEmpty()),
            capabilities = com.lastwave.app.ui.common.TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = "Home",
            onDismiss = { menuTrack = null },
        )
    }

    if (showJamSheet) {
        com.lastwave.app.ui.jam.LastWaveJamSheet(
            viewModel = jamViewModel,
            onDismiss = { showJamSheet = false },
        )
    }
}

internal suspend fun snapshotFlowNearEnd(listState: LazyListState, onNearEnd: () -> Unit) {
    snapshotFlow {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        total > 0 && lastVisible >= total - 5
    }.collect { isNear -> if (isNear) onNearEnd() }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class ArtistAlbumNavBridgeHome @javax.inject.Inject constructor(
    val navigator: com.lastwave.app.ui.navigation.ArtistAlbumNavigator
) : androidx.lifecycle.ViewModel()
