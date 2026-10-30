package com.lastwave.app.ui.feed

import androidx.activity.compose.BackHandler
import com.lastwave.app.playback.toPlayableTrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Mic
import com.lastwave.app.data.music.toYouTubeMusicTrack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.SharedFlow
import com.lastwave.app.ui.navigation.FEED_TAP_DEBOUNCE_MS
import com.lastwave.app.ui.navigation.FeedTapAction
import com.lastwave.app.ui.navigation.resolveFeedTapAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import com.lastwave.app.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.ui.feed.cards.CardTokens
import com.lastwave.app.ui.feed.cards.CardTextArea
import com.lastwave.app.ui.feed.cards.rememberCoverHeight
import com.lastwave.app.ui.feed.cards.rememberSquareCardWidth
import com.lastwave.app.ui.feed.cards.rememberWideCardWidth
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.data.feed.FeedAlbum
import com.lastwave.app.data.feed.FeedArtist
import com.lastwave.app.data.feed.FeedQuickTile
import com.lastwave.app.data.feed.FeedSpotlight
import com.lastwave.app.util.ArtistHelper
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.HomeSection
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.music.HomeChip
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.ExpressiveShuffleButton
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMiniTrayData
import com.lastwave.app.ui.common.TrackMiniTraySheet
import com.lastwave.app.ui.common.WindowSizeClass
import com.lastwave.app.ui.common.rememberWindowSizeClass
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.podcast.PodcastShowRow


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenGenerator: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenFriendProfile: (username: String, displayName: String?, avatarUrl: String?) -> Unit = { _, _, _ -> },
    onOpenNewReleases: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    onOpenPodcastShow: (String) -> Unit = {},
    onOpenYouTubeLogin: () -> Unit = {},
    feedTapEvents: SharedFlow<Unit>? = null,
    viewModel: FeedViewModel = hiltViewModel(),
    artistAlbumNavigator: ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeFeed>().navigator,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = state.selectedChip != null) {
        viewModel.selectChip(null)
    }
    val discoveryTracks by viewModel.discoveryTracks.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onVisible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val musicPlayer = LocalMusicPlayer.current
    val context = LocalContext.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()
    var menuTrack by remember { mutableStateOf<YouTubeMusicTrack?>(null) }
    // Long-press (deep press) mini tray â€” 3-dot buttons keep the full sheet.
    var miniTrayTrack by remember { mutableStateOf<TrackMiniTrayData?>(null) }
    // Sections hidden via Settings â†’ Home sections.
    val hiddenSections by viewModel.hiddenHomeSections.collectAsStateWithLifecycle()
    val ytHidden by viewModel.ytMusicHiddenSections.collectAsStateWithLifecycle()
    val ytOrder by viewModel.ytMusicSectionsOrder.collectAsStateWithLifecycle()
    val homeSectionsOrder by viewModel.homeSectionsOrder.collectAsStateWithLifecycle()
    val pinnedHomeSections by viewModel.pinnedHomeSections.collectAsStateWithLifecycle()
    var sectionMenuTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val podcastsEnabled by viewModel.podcastsEnabled.collectAsStateWithLifecycle()
    val topGenres by viewModel.topGenres.collectAsStateWithLifecycle()
    fun isSectionVisible(section: HomeSection) = section.id !in hiddenSections
    val snackbarHostState = remember { SnackbarHostState() }
    val hasFeedContent = state.isChipActive || with(state.feedData) {
        nativeShelves.any { it.items.isNotEmpty() } ||
            quickTiles.isNotEmpty() || mixes.isNotEmpty() || topArtists.isNotEmpty() ||
            quickPicks.isNotEmpty() || jumpBackIn.isNotEmpty() || recentAlbums.isNotEmpty() ||
            heavyRotation.isNotEmpty() || ytLikedSongs.isNotEmpty() || ytRecentSongs.isNotEmpty() ||
            (becauseYouListenTo?.items?.isNotEmpty() == true) || freshFinds.isNotEmpty() ||
            spotlight != null || charts.isNotEmpty() || newReleases.isNotEmpty() || friends.isNotEmpty() ||
            podcastShows.isNotEmpty()
    }
    val showSynthetic = !state.isChipActive
    val listState = rememberLazyListState()
    var lastFeedTapMillis by remember { mutableLongStateOf(0L) }
    var isFeedTapRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(state.isRefreshing) {
        if (!state.isRefreshing) {
            isFeedTapRefreshing = false
        }
    }

    LaunchedEffect(feedTapEvents) {
        feedTapEvents?.collect {
            val now = System.currentTimeMillis()
            val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val isChipSelected = state.selectedChip != null
            val isRefreshing = state.isRefreshing || isFeedTapRefreshing

            val action = resolveFeedTapAction(
                isChipSelected = isChipSelected,
                isAtTop = isAtTop,
                isOnHomeFeed = true,
                isRefreshing = isRefreshing,
                nowMs = now,
                lastTapMs = lastFeedTapMillis,
                debounceMs = FEED_TAP_DEBOUNCE_MS,
            )

            when (action) {
                FeedTapAction.DeselectChipAndRefresh -> {
                    lastFeedTapMillis = now
                    isFeedTapRefreshing = true
                    viewModel.selectChip(null)
                    listState.animateScrollToItem(0)
                    viewModel.refresh()
                }
                FeedTapAction.ScrollTopAndRefresh -> {
                    lastFeedTapMillis = now
                    isFeedTapRefreshing = true
                    listState.animateScrollToItem(0)
                    viewModel.refresh()
                }
                FeedTapAction.RefreshOnly -> {
                    lastFeedTapMillis = now
                    isFeedTapRefreshing = true
                    viewModel.refresh()
                }
                FeedTapAction.NavigateToFeed,
                FeedTapAction.Ignore -> { /* ignored */ }
            }
        }
    }
    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.pagedShelves.size, state.chipShelves.size) {
        if (shouldLoadMore && !state.isLoading && !state.isLoadingMore && !state.isRefreshing && !state.isChipLoading) {
            viewModel.loadMoreHomeShelves()
        }
    }
    val greeting = remember {
        when (java.time.LocalTime.now().hour) {
            in 5..11 -> context.getString(R.string.feed_greeting_morning)
            in 12..16 -> context.getString(R.string.feed_greeting_afternoon)
            in 17..21 -> context.getString(R.string.feed_greeting_evening)
            else -> context.getString(R.string.feed_greeting_night)
        }
    }
    val formattedDate = remember {
        try {
            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        } catch (_: Exception) { "" }
    }
    LaunchedEffect(state.error, hasFeedContent) {
        val error = state.error ?: return@LaunchedEffect
        if (hasFeedContent) {
            val result = snackbarHostState.showSnackbar(error, actionLabel = context.getString(R.string.feed_retry), withDismissAction = true)
            viewModel.dismissError()
            if (result == SnackbarResult.ActionPerformed) viewModel.refresh()
        }
    }
    val isPodcastsActive = state.selectedChip?.title.equals("Podcasts", ignoreCase = true)
    val podcastHeroScrollOffset by remember(listState) {
        derivedStateOf {
            val firstItem = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (firstItem == null || firstItem.index > 0) {
                10000f
            } else {
                listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (isPodcastsActive) {
            com.lastwave.app.ui.feed.cards.PodcastHeroBackground(
                backgroundUrl = state.chipBackgroundUrl,
                scrollOffsetY = podcastHeroScrollOffset,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 920.dp),
        ) {
            ExpressiveHeader(
                title = stringResource(R.string.feed_home),
                containerColor = if (isPodcastsActive) Color.Transparent else Color.Unspecified,
                actions = {
                    HeaderActionIcon(Icons.Filled.Download, "Downloads", onOpenDownloads)
                    HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                    IconButton(onClick = onOpenSettings) {
                        com.lastwave.app.ui.home.ProfileAvatar(avatarUrl = state.avatarUrl, modifier = Modifier.size(38.dp))
                    }
                },
            )

            // Pinned YTM chip row under the app bar â€” stays visible while the
            // feed scrolls beneath it. Rendered only when chips exist; its
            // loading/filtering/selection behaviour is unchanged.
            if (state.homeChips.isNotEmpty()) {
                PinnedChipsBar(
                    chips = state.homeChips,
                    selectedChip = state.selectedChip,
                    onChipClick = viewModel::onHomeChipSelected,
                    transparentBackground = isPodcastsActive,
                )
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.isLoading) {
                FeedLoadingSkeleton()
            } else if (!hasFeedContent) {
                FeedEmptyState(
                    message = if (state.error != null) {
                        "We couldn't load your recommendations. Try again, or find something in search."
                    } else {
                        "Search for a favorite or explore something new. Your music starts here."
                    },
                    onRetry = viewModel::loadFeed,
                    onOpenSearch = onOpenSearch,
                )
            } else {
                val individualTopArtists = remember(state.feedData.topArtists) {
                    state.feedData.topArtists.flatMap { artist ->
                        val split = ArtistHelper.splitArtists(artist.name)
                        if (split.size <= 1) {
                            listOf(artist.copy(name = ArtistHelper.primaryArtist(artist.name)))
                        } else {
                            split.map { singleName ->
                                FeedArtist(
                                    name = singleName,
                                    browseId = if (singleName.equals(artist.name, ignoreCase = true)) artist.browseId else null,
                                    artworkUrl = artist.artworkUrl,
                                )
                            }
                        }
                    }.distinctBy { it.name.trim().lowercase() }
                }

                val normalizedHidden = remember(ytHidden) {
                    ytHidden.map { it.trim().lowercase() }.toSet()
                }

                fun isTitleHidden(title: String): Boolean {
                    val t = title.trim().lowercase()
                    return normalizedHidden.any { h ->
                        t == h || t.startsWith(h) || h.startsWith(t) ||
                        (h == "listen again" && (t.contains("listen again") || t.contains("jump back"))) ||
                        (h == "quick picks" && (t.contains("quick pick") || t.contains("picked for you"))) ||
                        (h == "mixed for you" && (t.contains("mixed for you") || t.contains("mixes"))) ||
                        (h == "trending" && t.contains("trend")) ||
                        (h == "new releases" && (t.contains("new release") || t.contains("fresh drop"))) ||
                        (h == "community playlists" && t.contains("community")) ||
                        (h == "forgotten favorites" && t.contains("forgotten"))
                    }
                }

                val visibleNativeShelves = remember(state.feedData.nativeShelves, ytHidden, ytOrder) {
                    state.feedData.nativeShelves
                        .filter { shelf -> !isTitleHidden(shelf.title) && shelf.items.isNotEmpty() }
                        .let { list ->
                            if (ytOrder.isEmpty()) list
                            else {
                                list.sortedWith(compareBy { shelf ->
                                    val t = shelf.title.trim().lowercase()
                                    val idx = ytOrder.indexOfFirst { o ->
                                        val opt = o.trim().lowercase()
                                        t == opt || t.startsWith(opt) || opt.startsWith(t) ||
                                        (opt == "listen again" && (t.contains("listen again") || t.contains("jump back"))) ||
                                        (opt == "quick picks" && (t.contains("quick pick") || t.contains("picked for you"))) ||
                                        (opt == "mixed for you" && (t.contains("mixed for you") || t.contains("mixes"))) ||
                                        (opt == "trending" && t.contains("trend")) ||
                                        (opt == "new releases" && t.contains("new release")) ||
                                        (opt == "community playlists" && t.contains("community")) ||
                                        (opt == "forgotten favorites" && t.contains("forgotten")) ||
                                        (opt == "similar to" && t.contains("similar")) ||
                                        (opt == "from your library" && t.contains("library"))
                                    }
                                    if (idx >= 0) idx else 999
                                })
                            }
                        }
                }

                val quickTiles = remember(state.feedData.quickTiles, state.feedData.isYtConnected) {
                    if (state.feedData.isYtConnected) {
                        state.feedData.quickTiles
                    } else {
                        state.feedData.quickTiles.filter {
                            it.collection != "yt_liked" && it.collection != "yt_recent" &&
                                it.playlistId != "yt_liked" && it.playlistId != "yt_recent"
                        }
                    }
                }

                val activeContainerKeys = remember(
                    showSynthetic, hiddenSections, quickTiles, podcastsEnabled, state.feedData.podcastShows,
                    topGenres, state.feedData.tasteTags, state.isChipActive, state.isChipLoading,
                    state.chipShelves, visibleNativeShelves, state.feedData.quickPicks, discoveryTracks,
                    state.feedData.becauseYouListenTo, state.feedData.freshFinds, state.feedData.jumpBackIn,
                    state.feedData.mixes, individualTopArtists, state.feedData.heavyRotation,
                    state.feedData.recentAlbums, state.feedData.charts, state.feedData.newReleases,
                    state.feedData.friends, state.pagedShelves
                ) {
                    buildList {
                        if (showSynthetic && isSectionVisible(HomeSection.QUICK_TILES) && quickTiles.isNotEmpty()) add("quick_tiles")
                        if (showSynthetic && podcastsEnabled && state.feedData.podcastShows.isNotEmpty()) add("podcast_shelf")
                        if (showSynthetic) add("taste_strip")
                        if (state.isChipActive) {
                            if (state.isChipLoading) {
                                add("chip_loading_skeleton")
                            } else if (state.chipShelves.isEmpty()) {
                                add("chip_empty_state")
                            } else {
                                state.chipShelves.forEachIndexed { i, s ->
                                    if (s.items.isNotEmpty()) add("chip_shelf_${i}_${s.title}")
                                }
                            }
                        } else {
                            visibleNativeShelves.forEachIndexed { i, s ->
                                add("native_shelf_${i}_${s.title}")
                            }
                        }
                        if (showSynthetic && isSectionVisible(HomeSection.QUICK_PICKS) && !isTitleHidden("Quick picks") && state.feedData.quickPicks.isNotEmpty()) add("quick_picks")
                        if (showSynthetic && discoveryTracks.isNotEmpty()) add("discover_new_music")
                        if (showSynthetic && isSectionVisible(HomeSection.BECAUSE_YOU_LISTEN_TO) && state.feedData.becauseYouListenTo?.items?.isNotEmpty() == true) add("because_you_listen_to")
                        if (showSynthetic && isSectionVisible(HomeSection.FRESH_FINDS) && state.feedData.freshFinds.isNotEmpty()) add("fresh_finds")
                        if (showSynthetic && isSectionVisible(HomeSection.JUMP_BACK_IN) && !isTitleHidden("Listen again") && state.feedData.jumpBackIn.isNotEmpty()) add("jump_back_in")
                        if (showSynthetic && isSectionVisible(HomeSection.MIXES) && !isTitleHidden("Mixed for you") && state.feedData.mixes.isNotEmpty()) add("mixed_for_you")
                        if (showSynthetic && isSectionVisible(HomeSection.SPOTLIGHT) && state.feedData.spotlight != null) add("spotlight_hero")
                        if (showSynthetic && isSectionVisible(HomeSection.TOP_ARTISTS) && individualTopArtists.isNotEmpty()) add("top_artists")
                        if (showSynthetic && isSectionVisible(HomeSection.HEAVY_ROTATION) && state.feedData.heavyRotation.isNotEmpty()) add("heavy_rotation")
                        if (showSynthetic && isSectionVisible(HomeSection.ALBUMS) && state.feedData.recentAlbums.isNotEmpty()) add("albums_in_rotation")
                        if (showSynthetic && isSectionVisible(HomeSection.CHARTS) && state.feedData.charts.isNotEmpty()) add("trending_charts")
                        if (showSynthetic && isSectionVisible(HomeSection.NEW_RELEASES) && !isTitleHidden("New releases") && state.feedData.newReleases.isNotEmpty()) add("new_releases")
                        if (isSectionVisible(HomeSection.FRIENDS) && state.feedData.friends.isNotEmpty()) add("friends_activity")
                        if (!state.isChipActive) {
                            state.pagedShelves.forEachIndexed { i, s ->
                                if (s.items.isNotEmpty() && !isTitleHidden(s.title)) add("paged_shelf_${i}_${s.title}")
                            }
                        }
                    }
                }

                val hasHeroAbove = showSynthetic && isSectionVisible(HomeSection.HERO)
                val spotlightIndex = activeContainerKeys.indexOf("spotlight_hero")
                val getContainerShape: (String) -> RoundedCornerShape = { key ->
                    val index = activeContainerKeys.indexOf(key)
                    if (index == -1) {
                        RoundedCornerShape(0.dp)
                    } else {
                        val isFirst = index == 0
                        val isLast = index == activeContainerKeys.size - 1
                        val isBeforeSpotlight = spotlightIndex != -1 && index == spotlightIndex - 1
                        val isAfterSpotlight = spotlightIndex != -1 && index == spotlightIndex + 1

                        val topRadius = if ((isFirst && !hasHeroAbove) || isAfterSpotlight) 28.dp else 0.dp
                        val bottomRadius = if (isLast || isBeforeSpotlight) 28.dp else 0.dp

                        RoundedCornerShape(
                            topStart = topRadius,
                            topEnd = topRadius,
                            bottomStart = bottomRadius,
                            bottomEnd = bottomRadius,
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    contentPadding = PaddingValues(
                        bottom = FloatingNavDefaults.contentBottomPadding(),
                        top = 12.dp,
                    ),
                    verticalArrangement = Arrangement.Top,
                ) {
                    if (showSynthetic && isSectionVisible(HomeSection.HERO)) {
                    item(key = "hero") {
                        Column(
                            modifier = Modifier.padding(bottom = 3.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp)
                                    .padding(top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = greeting,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.3).sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (formattedDate.isNotBlank()) {
                                    Text(
                                        text = formattedDate,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                    )
                                }
                            }
                            InfiniteRadioHero(
                                quickPicks = state.feedData.quickPicks,
                                onPlay = viewModel::playInfiniteRadio,
                            )
                        }
                    }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.QUICK_TILES) && quickTiles.isNotEmpty()) {
                        item(key = "quick_tiles") {
                            HomeContainerSection(
                                shape = getContainerShape("quick_tiles"),
                            ) {
                            FeedSectionHeader(
                                title = stringResource(R.string.feed_quick_access),
                                onLongClick = { sectionMenuTarget = HomeSection.QUICK_TILES.id to "Quick Access" },
                            )
                                    Spacer(modifier = Modifier.height(12.dp))
                            QuickTilesGrid(
                                tiles = quickTiles,
                                onTileClick = { tile ->
                                    when {
                                        tile.collection == "live_map" -> {
                                            runCatching {
                                                androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                                                    .launchUrl(context, android.net.Uri.parse("https://telemetry.clashprojects.qd.je"))
                                            }.onFailure {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://telemetry.clashprojects.qd.je")).apply {
                                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { context.startActivity(intent) }
                                            }
                                        }
                                        tile.collection == "radio" -> onOpenDiscover()
                                        tile.collection == "discover_mix" || tile.title.contains("Discover Mix", ignoreCase = true) -> {
                                            if (!tile.playlistId.isNullOrBlank()) {
                                                onOpenFeedPlaylist(tile.playlistId)
                                            } else {
                                                viewModel.openDiscoverMixPlaylist(tile) { resolvedId ->
                                                    onOpenFeedPlaylist(resolvedId)
                                                }
                                            }
                                        }
                                        tile.collection == "mix" -> onOpenGenerator()
                                        tile.collection == "my_mix" -> {
                                            if (tile.playlistId != null) {
                                                viewModel.playPlaylistById(tile.playlistId, tile.title)
                                            } else {
                                                viewModel.playInfiniteRadio()
                                            }
                                        }
                                        tile.collection == "yt_liked" || tile.playlistId == "yt_liked" -> onOpenFeedPlaylist("yt_liked")
                                        tile.collection == "yt_recent" || tile.playlistId == "yt_recent" -> onOpenFeedPlaylist("yt_recent")
                                        tile.collection == "new_releases" -> onOpenNewReleases()
                                        tile.localPlaylistId != null -> onOpenPlaylist(tile.localPlaylistId)
                                        tile.playlistId != null -> onOpenFeedPlaylist(tile.playlistId)
                                        else -> viewModel.handleQuickTileClick(tile)
                                    }
                                },
                            )
                        
                            }
                        }
                    }

                    if (showSynthetic && podcastsEnabled && state.feedData.podcastShows.isNotEmpty()) {
                        item(key = "podcast_shelf") {
                            HomeContainerSection(
                                shape = getContainerShape("podcast_shelf"),
                            ) {
                                FeedSectionHeader(
                                    title = state.feedData.podcastShelfTitle
                                        ?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.podcasts_title),
                                    actionText = stringResource(R.string.podcasts_see_all),
                                    actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    onActionClick = {
                                        val chip = state.homeChips.firstOrNull { it.title.equals("Podcasts", ignoreCase = true) }
                                            ?: HomeChip(title = "Podcasts", params = "ggMPOg1mZWVkX3BvZGNhc3Rz")
                                        viewModel.onHomeChipSelected(chip)
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                                FeedMediaRow(
                                    content = {
                                        items(
                                            state.feedData.podcastShows,
                                            key = { "popular_show_${it.showId}" },
                                            contentType = { "podcast_show" },
                                        ) { show ->
                                            Box(Modifier.animateItem()) {
                                                com.lastwave.app.ui.feed.cards.ShelfSquareCard(
                                                    title = show.title,
                                                    infoLine = show.publisher.orEmpty(),
                                                    artworkUrl = show.artworkUrl,
                                                    fallbackIcon = Icons.Filled.Mic,
                                                    onClick = { onOpenPodcastShow(show.showId) },
                                                    cardWidth = com.lastwave.app.ui.feed.cards.rememberSquareCardWidth(),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // 3. "Your Sound" Section - ALWAYS visible below Quick Access
                    if (showSynthetic) {
                        val displayTags = topGenres.ifEmpty {
                            state.feedData.tasteTags.ifEmpty {
                                listOf("Rock", "Indie", "Pop", "Electronic", "Hip-Hop", "R&B", "Synthwave", "Alternative")
                            }
                        }
                        item(key = "taste_strip") {
                            HomeContainerSection(
                                shape = getContainerShape("taste_strip"),
                            ) {
                                TasteStrip(
                                    tags = displayTags,
                                    launching = state.launchingRadio,
                                    onTagClick = { tag -> viewModel.playDiscoveryQuery(tag.displayName(), tag) },
                                )
                            }
                        }
                    }
                    // ...) are pinned at the top under the app bar â€” see the
                    // PinnedChipsBar rendered above the LazyColumn. Loading,
                    // filtering and selection logic are unchanged; only the
                    // row's position moved from between feed sections to the top.

                    if (state.isChipActive) {
                        if (state.isChipLoading) {
                            // Card-shaped shimmer placeholders that mirror the
                            // native shelf layout the chip page will render.
                            item(key = "chip_loading_skeleton") {
                                HomeContainerSection(
                                    shape = getContainerShape("chip_loading_skeleton"),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(CardTokens.sectionGap)) {
                                        com.lastwave.app.ui.feed.cards.ShelfSquareSkeletonRow(
                                            cardWidth = com.lastwave.app.ui.feed.cards.rememberSquareCardWidth(),
                                        )
                                        com.lastwave.app.ui.feed.cards.ShelfWideSkeletonRow(
                                            cardWidth = com.lastwave.app.ui.feed.cards.rememberWideCardWidth(),
                                        )
                                    }
                                }
                            }
                        } else if (state.chipShelves.isEmpty()) {
                            item(key = "chip_empty_state") {
                                HomeContainerSection(
                                    shape = getContainerShape("chip_empty_state"),
                                ) {
                                    PodcastGuestEmptyState(
                                        onLoginClick = onOpenYouTubeLogin,
                                    )
                                }
                            }
                        } else {
                            for ((shelfIndex, shelf) in state.chipShelves.withIndex()) {
                                if (shelf.items.isEmpty()) continue
                                item(key = "chip_shelf_${shelfIndex}_${shelf.title}") {
                                    HomeContainerSection(
                                        shape = getContainerShape("chip_shelf_${shelfIndex}_${shelf.title}"),
                                    ) {
                                        HomeShelfCard(
                                        shelf = shelf,
                                        onPlayTrack = { track, title -> viewModel.playTrack(track, title) },
                                        onPlayTracksQueue = { tracks, idx, title -> viewModel.playTracksQueue(tracks, idx, title) },
                                        onShuffleTracksQueue = { tracks, title -> viewModel.shuffleTracksQueue(tracks, title) },
                                        onOpenFeedPlaylist = onOpenFeedPlaylist,
                                        onOpenAlbum = { title, artist, id -> artistAlbumNavigator.openAlbum(title, artist, id) },
                                        onTrackLongClick = { tray -> miniTrayTrack = tray },
                                        onPlayPlaylist = viewModel::playPlaylistSummary,
                                        onTrackMenu = { menuTrack = it },
                                        onOpenPodcastShow = onOpenPodcastShow,
                                        onPlayPodcastEpisode = { ep, q, title -> viewModel.playPodcastEpisode(ep, q, title) },
                                        onHeaderLongClick = { title -> sectionMenuTarget = "yt:$title" to title },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Native YouTube Music shelves (filtered & ordered via Settings â†’ Home sections â†’ YouTube Music Selection)
                        for ((shelfIndex, shelf) in visibleNativeShelves.withIndex()) {
                            item(key = "native_shelf_${shelfIndex}_${shelf.title}") {
                                HomeContainerSection(
                                    shape = getContainerShape("native_shelf_${shelfIndex}_${shelf.title}"),
                                ) {
                                    HomeShelfCard(
                                    shelf = shelf,
                                    onPlayTrack = { track, title -> viewModel.playTrack(track, title) },
                                    onPlayTracksQueue = { tracks, idx, title -> viewModel.playTracksQueue(tracks, idx, title) },
                                    onShuffleTracksQueue = { tracks, title -> viewModel.shuffleTracksQueue(tracks, title) },
                                    onOpenFeedPlaylist = onOpenFeedPlaylist,
                                    onOpenAlbum = { title, artist, id -> artistAlbumNavigator.openAlbum(title, artist, id) },
                                    onTrackLongClick = { tray -> miniTrayTrack = tray },
                                    onPlayPlaylist = viewModel::playPlaylistSummary,
                                    onTrackMenu = { menuTrack = it },
                                    onOpenPodcastShow = onOpenPodcastShow,
                                    onPlayPodcastEpisode = { ep, q, title -> viewModel.playPodcastEpisode(ep, q, title) },
                                    onHeaderLongClick = { title -> sectionMenuTarget = "yt:$title" to title },
                                    )
                                }
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.QUICK_PICKS) && !isTitleHidden("Quick picks") && state.feedData.quickPicks.isNotEmpty()) {
                        item(key = "quick_picks") {
                            HomeContainerSection(
                                shape = getContainerShape("quick_picks"),
                            ) {
                            val title = if (state.feedData.hasYtRecommendations) stringResource(R.string.feed_picked_you) else stringResource(R.string.feed_quick_picks)
                            val subtitle = if (state.feedData.hasYtRecommendations) {
                                stringResource(R.string.feed_from_listening)
                            } else if (state.feedData.hasPersonalContent) {
                                stringResource(R.string.feed_matched_taste)
                            } else {
                                stringResource(R.string.feed_trending_worth)
                            }
                            FeedSectionHeader(
                                title = title,
                                subtitle = subtitle,
                                onShuffleClick = { viewModel.shuffleTracksQueue(state.feedData.quickPicks, "Quick Picks") },
                                onLongClick = { sectionMenuTarget = HomeSection.QUICK_PICKS.id to title },
                            )
                            QuickPicksRows(
                                tracks = state.feedData.quickPicks,
                                currentPlayingVideoId = playbackState.current?.videoId,
                                isPlaying = playbackState.isPlaying,
                                onTrackClick = { index ->
                                    val track = state.feedData.quickPicks.getOrNull(index)
                                    if (track != null) {
                                        viewModel.playTrack(track, "Quick Picks")
                                    } else {
                                        viewModel.playTracksQueue(state.feedData.quickPicks, index, "Quick Picks")
                                    }
                                },
                                onMenuClick = { menuTrack = it },
                                onLongClick = { track, index ->
                                    miniTrayTrack = TrackMiniTrayData(
                                        title = track.title,
                                        artist = track.artist,
                                        album = track.album,
                                        artworkUrl = track.artworkUrl,
                                        videoId = track.videoId.takeIf(String::isNotBlank),
                                        sourceLabel = "Home",
                                        onPlay = { viewModel.playTrack(track, "Quick Picks") },
                                    )
                                },
                            )
                        
                            }
                        }
                    }

                    if (showSynthetic && discoveryTracks.isNotEmpty()) {
                        item(key = "discover_new_music") {
                            HomeContainerSection(
                                shape = getContainerShape("discover_new_music"),
                            ) {
                                    FeedSectionHeader(
                                        title = stringResource(R.string.feed_discover),
                                        subtitle = stringResource(R.string.feed_discover_sub),
                                        actionText = stringResource(R.string.feed_see_all),
                                        actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                        onActionClick = onOpenDiscover,
                                    )
                                    FeedMediaRow(
                                        content = {
                                            itemsIndexed(discoveryTracks) { index, track ->
                                                val playable = track.toPlayableTrack()
                                                FeedMediaCard(
                                                    title = track.name,
                                                    subtitle = ArtistHelper.primaryArtist(track.artist),
                                                    artworkUrl = track.artworkUrl,
                                                    fallbackIcon = Icons.Filled.Explore,
                                                    badgeText = "DISCOVER",
                                                    onClick = {
                                                        val queue = discoveryTracks.map { it.toPlayableTrack() }
                                                        musicPlayer.playDiscoverQueue(queue, index)
                                                    },
                                                    onPlayClick = {
                                                        val queue = discoveryTracks.map { it.toPlayableTrack() }
                                                        musicPlayer.playDiscoverQueue(queue, index)
                                                    },
                                                )
                                            }
                                        },
                                    )
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.BECAUSE_YOU_LISTEN_TO)) {
                    state.feedData.becauseYouListenTo?.takeIf { it.items.isNotEmpty() }?.let { section ->
                        item(key = "because_you_listen_to") {
                            HomeContainerSection(
                                shape = getContainerShape("because_you_listen_to"),
                            ) {
                            FeedSectionHeader(
                                title = section.title,
                                subtitle = section.subtitle,
                                onShuffleClick = { viewModel.shuffleTracksQueue(section.items, section.title) },
                                onLongClick = { sectionMenuTarget = HomeSection.BECAUSE_YOU_LISTEN_TO.id to section.title },
                            )
                            FeedMediaRow(
                                content = {
                                    itemsIndexed(section.items) { index, track ->
                                        FeedMediaCard(
                                            title = track.title,
                                            subtitle = ArtistHelper.primaryArtist(track.artist),
                                            artworkUrl = track.artworkUrl,
                                            fallbackIcon = Icons.Filled.MusicNote,
                                            onClick = { viewModel.playTrack(track, section.title) },
                                            onLongClick = {
                                                miniTrayTrack = TrackMiniTrayData(
                                                    title = track.title,
                                                    artist = track.artist,
                                                    album = track.album,
                                                    artworkUrl = track.artworkUrl,
                                                    videoId = track.videoId.takeIf(String::isNotBlank),
                                                    sourceLabel = "Home",
                                                    onPlay = { viewModel.playTrack(track, section.title) },
                                                )
                                            },
                                            onPlayClick = { viewModel.playTrack(track, section.title) },
                                        )
                                    }
                                },
                            )
                        
                            }
                        }
                    }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.FRESH_FINDS) && state.feedData.freshFinds.isNotEmpty()) {
                        item(key = "fresh_finds") {
                            HomeContainerSection(
                                shape = getContainerShape("fresh_finds"),
                            ) {
                                    FeedSectionHeader(
                                        title = stringResource(R.string.feed_fresh),
                                        subtitle = stringResource(R.string.feed_fresh_sub),
                                        onShuffleClick = { viewModel.shuffleTracksQueue(state.feedData.freshFinds, "Fresh Finds") },
                                        onLongClick = { sectionMenuTarget = HomeSection.FRESH_FINDS.id to "Fresh Finds" },
                                    )
                                    FeedMediaRow(
                                        content = {
                                            itemsIndexed(state.feedData.freshFinds) { index, track ->
                                                FeedMediaCard(
                                                    title = track.title,
                                                    subtitle = ArtistHelper.primaryArtist(track.artist),
                                                    artworkUrl = track.artworkUrl,
                                                    fallbackIcon = Icons.Filled.Whatshot,
                                                    badgeText = "NEW",
                                                    onLongClick = {
                                                        miniTrayTrack = TrackMiniTrayData(
                                                            title = track.title,
                                                            artist = track.artist,
                                                            album = track.album,
                                                            artworkUrl = track.artworkUrl,
                                                            videoId = track.videoId.takeIf(String::isNotBlank),
                                                            sourceLabel = "Home",
                                                            onPlay = { viewModel.playTrack(track, "Fresh Finds") },
                                                        )
                                                    },
                                                    onClick = { viewModel.playTrack(track, "Fresh Finds") },
                                                    onPlayClick = { viewModel.playTrack(track, "Fresh Finds") },
                                                )
                                            }
                                        },
                                    )
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.JUMP_BACK_IN) && !isTitleHidden("Listen again") && state.feedData.jumpBackIn.isNotEmpty()) {
                        item(key = "jump_back_in") {
                            HomeContainerSection(
                                shape = getContainerShape("jump_back_in"),
                            ) {
                            FeedSectionHeader(
                                title = stringResource(R.string.feed_jump),
                                subtitle = stringResource(R.string.feed_jump_sub),
                                onShuffleClick = { viewModel.playRecentQueue(state.feedData.jumpBackIn.shuffled(), 0) },
                                onLongClick = { sectionMenuTarget = HomeSection.JUMP_BACK_IN.id to "Jump Back In" },
                            )
                            FeedMediaRow(
                                content = {
                                    itemsIndexed(state.feedData.jumpBackIn) { index, track ->
                                        RecentTrackCard(
                                            track = track,
                                            onClick = { viewModel.playRecentQueue(state.feedData.jumpBackIn, index) },
                                            onLongClick = {
                                                miniTrayTrack = TrackMiniTrayData(
                                                    title = track.name,
                                                    artist = track.artist.displayName,
                                                    artworkUrl = track.artworkUrl,
                                                    sourceLabel = "Home",
                                                    onPlay = { viewModel.playRecentQueue(state.feedData.jumpBackIn, index) },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.MIXES) && !isTitleHidden("Mixed for you") && state.feedData.mixes.isNotEmpty()) {
                        item(key = "mixed_for_you") {
                            HomeContainerSection(
                                shape = getContainerShape("mixed_for_you"),
                            ) {
                            FeedSectionHeader(
                                title = stringResource(R.string.feed_mixes),
                                subtitle = stringResource(R.string.feed_mixes_sub),
                                onShuffleClick = {
                                    state.feedData.mixes.randomOrNull()?.let(viewModel::playMix)
                                },
                                onLongClick = { sectionMenuTarget = HomeSection.MIXES.id to "Mixed For You" },
                            )
                            FeedMediaRow(
                                content = {
                                    items(
                                        state.feedData.mixes,
                                        key = { it.seed.videoId },
                                        contentType = { "feed_mix" },
                                    ) { mix ->
                                        FeedPlaylistCard(
                                            title = mix.title,
                                            subtitle = stringResource(R.string.feed_endless_radio),
                                            artworkUrl = mix.seed.artworkUrl,
                                            onClick = { viewModel.playMix(mix) },
                                            onPlayClick = { viewModel.playMix(mix) },
                                        )
                                    }
                                },
                            )
                        
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.SPOTLIGHT)) {
                        state.feedData.spotlight?.let { spotlight ->
                            item(key = "spotlight_hero") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 3.dp),
                                ) {
                                    SpotlightHeroCard(
                                        spotlight = spotlight,
                                        onPlayRadio = {
                                            viewModel.playArtistRadio(FeedArtist(ArtistHelper.primaryArtist(spotlight.artistName), spotlight.browseId, spotlight.artworkUrl))
                                        },
                                        onOpenArtist = {
                                            artistAlbumNavigator.openArtist(ArtistHelper.primaryArtist(spotlight.artistName), spotlight.browseId ?: "")
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.TOP_ARTISTS) && individualTopArtists.isNotEmpty()) {
                        item(key = "top_artists") {
                            HomeContainerSection(
                                shape = getContainerShape("top_artists"),
                            ) {
                            FeedSectionHeader(
                                title = stringResource(R.string.feed_artists),
                                subtitle = stringResource(R.string.feed_artists_sub),
                                onLongClick = { sectionMenuTarget = HomeSection.TOP_ARTISTS.id to "Top Artists" },
                            )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.padding(top = 12.dp),
                                ) {
                                    itemsIndexed(individualTopArtists) { index, artist ->
                                        ArtistAvatarCard(
                                            artist = artist,
                                            isTop = index < 3,
                                            onClick = {
                                                artistAlbumNavigator.openArtist(
                                                    name = ArtistHelper.primaryArtist(artist.name),
                                                    browseId = artist.browseId ?: "",
                                                )
                                            },
                                        )
                                    }
                                }
                            
                            }
                        }
                        }

                    if (showSynthetic && isSectionVisible(HomeSection.HEAVY_ROTATION) && state.feedData.heavyRotation.isNotEmpty()) {
                        item(key = "heavy_rotation") {
                            HomeContainerSection(
                                shape = getContainerShape("heavy_rotation"),
                            ) {
                            FeedSectionHeader(
                                title = stringResource(R.string.feed_favorites),
                                subtitle = stringResource(R.string.feed_favorites_sub),
                                onShuffleClick = { viewModel.playGeneratedQueue(state.feedData.heavyRotation.shuffled(), 0, "Heavy Rotation") },
                                onLongClick = { sectionMenuTarget = HomeSection.HEAVY_ROTATION.id to "Heavy Rotation" },
                            )
                            FeedMediaRow(
                                content = {
                                    itemsIndexed(state.feedData.heavyRotation) { index, track ->
                                        FeedMediaCard(
                                            title = track.name,
                                            subtitle = ArtistHelper.primaryArtist(track.artist),
                                            artworkUrl = track.artworkUrl,
                                            fallbackIcon = Icons.Filled.MusicNote,
                                            onClick = { viewModel.playGeneratedQueue(state.feedData.heavyRotation, index, "Heavy Rotation") },
                                            onLongClick = {
                                                miniTrayTrack = TrackMiniTrayData(
                                                    title = track.name,
                                                    artist = track.artist,
                                                    artworkUrl = track.artworkUrl,
                                                    sourceLabel = "Home",
                                                    onPlay = { viewModel.playGeneratedQueue(state.feedData.heavyRotation, index, "Heavy Rotation") },
                                                )
                                            },
                                            onPlayClick = { viewModel.playGeneratedQueue(state.feedData.heavyRotation, index, "Heavy Rotation") },
                                        )
                                    }
                                },
                            )
                        
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.ALBUMS) && state.feedData.recentAlbums.isNotEmpty()) {
                        item(key = "albums_in_rotation") {
                            HomeContainerSection(
                                shape = getContainerShape("albums_in_rotation"),
                            ) {
                            FeedSectionHeader(
                                title = stringResource(R.string.feed_albums),
                                subtitle = stringResource(R.string.feed_albums_sub),
                                onLongClick = { sectionMenuTarget = HomeSection.ALBUMS.id to "Albums in Rotation" },
                            )
                            FeedMediaRow(
                                content = {
                                    items(
                                        state.feedData.recentAlbums,
                                        key = { "album_${it.browseId ?: it.title}_${it.artist}" },
                                        contentType = { "recent_album" },
                                    ) { album ->
                                        FeedMediaCard(
                                            title = album.title,
                                            subtitle = ArtistHelper.primaryArtist(album.artist),
                                            artworkUrl = album.artworkUrl,
                                            fallbackIcon = Icons.Filled.Album,
                                            onClick = {
                                                artistAlbumNavigator.openAlbum(
                                                    title = album.title,
                                                    artist = ArtistHelper.primaryArtist(album.artist),
                                                    browseId = album.browseId ?: "",
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.CHARTS) && state.feedData.charts.isNotEmpty()) {
                        item(key = "trending_charts") {
                            HomeContainerSection(
                                shape = getContainerShape("trending_charts"),
                            ) {
                            FeedSectionHeader(
                                title = stringResource(R.string.feed_trending),
                                subtitle = stringResource(R.string.feed_trending_sub),
                                onShuffleClick = { viewModel.shuffleTracksQueue(state.feedData.charts, "Top Charts") },
                                onLongClick = { sectionMenuTarget = HomeSection.CHARTS.id to "Trending Charts" },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                itemsIndexed(state.feedData.charts.take(15)) { index, track ->
                                    ChartTrackCard(
                                        rank = index + 1,
                                        track = track,
                                        onClick = { viewModel.playTrack(track, "Top Charts") },
                                        onLongClick = {
                                            miniTrayTrack = TrackMiniTrayData(
                                                title = track.title,
                                                artist = track.artist,
                                                album = track.album,
                                                artworkUrl = track.artworkUrl,
                                                videoId = track.videoId.takeIf(String::isNotBlank),
                                                sourceLabel = "Home",
                                                onPlay = { viewModel.playTrack(track, "Top Charts") },
                                            )
                                        },
                                    )
                                }
                            }
                        
                            }
                        }
                    }

                    if (showSynthetic && isSectionVisible(HomeSection.NEW_RELEASES) && !isTitleHidden("New releases") && state.feedData.newReleases.isNotEmpty()) {
                        item(key = "new_releases") {
                            HomeContainerSection(
                                shape = getContainerShape("new_releases"),
                            ) {
                            FeedSectionHeader(
                                title = stringResource(R.string.feed_releases),
                                subtitle = stringResource(R.string.feed_releases_sub),
                                actionText = stringResource(R.string.feed_see_all),
                                actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                onActionClick = onOpenNewReleases,
                                onLongClick = { sectionMenuTarget = HomeSection.NEW_RELEASES.id to "New Releases" },
                            )
                            FeedMediaRow(
                                content = {
                                    items(
                                        state.feedData.newReleases,
                                        key = { "release_${it.id}" },
                                        contentType = { "new_release" },
                                    ) { summary ->
                                        FeedMediaCard(
                                            title = summary.title,
                                            subtitle = summary.author?.let(ArtistHelper::primaryArtist) ?: "Album",
                                            artworkUrl = summary.artworkUrl,
                                            fallbackIcon = Icons.Filled.NewReleases,
                                            badgeText = "NEW",
                                            onClick = {
                                                artistAlbumNavigator.openAlbum(
                                                    title = summary.title,
                                                    artist = summary.author?.let(ArtistHelper::primaryArtist) ?: "",
                                                    browseId = summary.id,
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        
                            }
                        }
                    }

                    if (isSectionVisible(HomeSection.FRIENDS) && state.feedData.friends.isNotEmpty()) {
                        item(key = "friends_activity") {
                            HomeContainerSection(
                                shape = getContainerShape("friends_activity"),
                            ) {
                                    FeedSectionHeader(
                                        title = stringResource(R.string.feed_friends),
                                        subtitle = stringResource(R.string.feed_friends_sub),
                                        actionText = stringResource(R.string.feed_see_all),
                                        actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                        onActionClick = onOpenFriends,
                                        onLongClick = { sectionMenuTarget = HomeSection.FRIENDS.id to "Friends Activity" },
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier.padding(top = 12.dp),
                                    ) {
                                        items(
                                            state.feedData.friends,
                                            key = { "friend_${it.name}" },
                                            contentType = { "friend_avatar" },
                                        ) { friend ->
                                            FriendAvatarCard(
                                                friend = friend,
                                                onClick = {
                                                    onOpenFriendProfile(friend.name, friend.displayName, friend.avatarUrl)
                                                },
                                            )
                                        }
                                    }
                            }
                        }
                    }

                    if (!state.isChipActive) {
                        for ((shelfIndex, shelf) in state.pagedShelves.withIndex()) {
                            if (shelf.items.isEmpty() || isTitleHidden(shelf.title)) continue
                            item(key = "paged_shelf_${shelfIndex}_${shelf.title}") {
                                HomeContainerSection(
                                    shape = getContainerShape("paged_shelf_${shelfIndex}_${shelf.title}"),
                                ) {
                                    HomeShelfCard(
                                    shelf = shelf,
                                    onPlayTrack = { track, title -> viewModel.playTrack(track, title) },
                                    onPlayTracksQueue = { tracks, idx, title -> viewModel.playTracksQueue(tracks, idx, title) },
                                    onShuffleTracksQueue = { tracks, title -> viewModel.shuffleTracksQueue(tracks, title) },
                                    onOpenFeedPlaylist = onOpenFeedPlaylist,
                                    onOpenAlbum = { title, artist, id -> artistAlbumNavigator.openAlbum(title, artist, id) },
                                    onTrackLongClick = { tray -> miniTrayTrack = tray },
                                    onPlayPlaylist = viewModel::playPlaylistSummary,
                                    onTrackMenu = { menuTrack = it },
                                    onOpenPodcastShow = onOpenPodcastShow,
                                    onPlayPodcastEpisode = { ep, q, title -> viewModel.playPodcastEpisode(ep, q, title) },
                                    )
                                }
                            }
                        }
                    }

                    if (state.isLoadingMore) {
                        item(key = "loading_more_indicator", contentType = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                ExpressiveInlineLoadingIndicator()
                            }
                        }
                    }

                    if (showSynthetic) {
                        item(key = "feed_footer") {
                            FeedFooter(lastUpdatedMillis = state.feedData.lastUpdatedMillis)
                        }
                    }
                }
            }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .adaptiveContentWidth(maxWidth = 600.dp)
                .safeHorizontalContentPadding()
                .padding(bottom = FloatingNavDefaults.contentBottomPadding()),
        )
    }


    menuTrack?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(showCopyActions = false, showDeleteScrobble = false),
            playableTrack = PlayableTrack(
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.artworkUrl,
                videoId = track.videoId.takeIf(String::isNotBlank),
            ),
            playbackSourceLabel = "Home",
            onDismiss = { menuTrack = null },
        )
    }

    miniTrayTrack?.let { tray ->
        TrackMiniTraySheet(
            data = tray,
            onDismiss = { miniTrayTrack = null },
        )
    }

    sectionMenuTarget?.let { (key, name) ->
        val isPinned = key in pinnedHomeSections
        AlertDialog(
            onDismissRequest = { sectionMenuTarget = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = {
                            viewModel.togglePinHomeSection(key)
                            sectionMenuTarget = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                if (isPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                if (isPinned) "Unpin from top" else "Pin at the top",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Surface(
                        onClick = {
                            viewModel.hideHomeSection(key)
                            sectionMenuTarget = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.VisibilityOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "Hide section",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sectionMenuTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun String.displayName(): String =
    split(" ", "-", "_").filter { it.isNotBlank() }.joinToString(" ") {
        it.replaceFirstChar { c -> c.uppercase() }
    }

private fun relativeTime(context: android.content.Context, uts: String?): String? {
    val epoch = uts?.toLongOrNull() ?: return null
    val now = System.currentTimeMillis() / 1000
    val diff = (now - epoch).coerceAtLeast(0)
    return when {
        diff < 3600 -> context.getString(R.string.feed_ago_min, (diff / 60).coerceAtLeast(1).toInt())
        diff < 86400 -> context.getString(R.string.feed_ago_hour, (diff / 3600).toInt())
        diff < 86400 * 7 -> context.getString(R.string.feed_ago_day, (diff / 86400).toInt())
        diff < 86400 * 30 -> context.getString(R.string.feed_ago_week, (diff / (86400 * 7)).toInt())
        else -> context.getString(R.string.feed_ago_month, (diff / (86400 * 30)).toInt())
    }
}

@Composable
private fun InfiniteRadioHero(
    quickPicks: List<YouTubeMusicTrack>,
    onPlay: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val heroArt = quickPicks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
    val heroShape = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )
    Surface(
        shape = heroShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (!heroArt.isNullOrBlank()) {
                AsyncImage(
                    model = heroArt,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(208.dp)
                        .alpha(0.32f),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(208.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                "MADE FOR YOU",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Text(
                    text = "Infinite Radio",
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, letterSpacing = (-0.4).sp),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "An endless station shaped by your listening",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlay()
                        },
                        enabled = quickPicks.isNotEmpty(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        modifier = Modifier.height(44.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.feed_play_cd), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TasteStrip(
    tags: List<String>,
    launching: String?,
    onTagClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FeedSectionHeader(
            title = stringResource(R.string.feed_your_sound),
            subtitle = stringResource(R.string.feed_your_sound_sub),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            items(tags, key = { it }, contentType = { "filter_tag" }) { tag ->
                val isLoading = launching?.equals(tag.displayName(), ignoreCase = true) == true
                Surface(
                    onClick = { onTagClick(tag) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    modifier = Modifier,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            tag.displayName(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isLoading) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.feed_start_radio_cd),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Horizontally scrolling row of YouTube Music mood/activity chips
 * (e.g., Relax, Energize, Workout, Commute, Focus).
 * Placed directly above the native YTM shelves using the app's liquid-glass style.
 * Adapted from Metrolist's Home screen chip row (GPL-3.0).
 */
@Composable
fun HomeChipsRow(
    chips: List<HomeChip>,
    selectedChip: HomeChip?,
    onChipClick: (HomeChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(chips, key = { it.title }, contentType = { "filter_chip" }) { chip ->
            val isSelected = chip == selectedChip
            Surface(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onChipClick(chip)
                },
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = chip.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/**
 * Pinned container for the YTM chip row. Sits directly under the app bar and
 * stays visible while the feed scrolls, with a subtle gradient scrim behind
 * it so content sliding underneath stays legible. Liquid-glass styled.
 */
@Composable
private fun PinnedChipsBar(
    chips: List<HomeChip>,
    selectedChip: HomeChip?,
    onChipClick: (HomeChip) -> Unit,
    transparentBackground: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (transparentBackground) {
                    Modifier
                } else {
                    Modifier.background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                            0.7f to MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                            1f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        )
                    )
                }
            ),
    ) {
        HomeChipsRow(
            chips = chips,
            selectedChip = selectedChip,
            onChipClick = onChipClick,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
    }
}

/**
 * Reusable surface card for a YouTube Music carousel shelf.
 * Renders each item through the card system (square / wide / featured /
 * community) chosen from the item's real YTM data, falling back to square.
 * Used for page-1 native shelves, continuation paged shelves, and chip shelves.
 */
@Composable
private fun HomeShelfCard(
    shelf: HomeShelf,
    onPlayTrack: (YouTubeMusicTrack, String) -> Unit,
    onPlayTracksQueue: (List<YouTubeMusicTrack>, Int, String) -> Unit,
    onShuffleTracksQueue: (List<YouTubeMusicTrack>, String) -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String) -> Unit,
    onTrackLongClick: (TrackMiniTrayData) -> Unit,
    onPlayPlaylist: (com.lastwave.app.data.music.YouTubePlaylistSummary) -> Unit,
    onTrackMenu: (YouTubeMusicTrack) -> Unit,
    onOpenPodcastShow: (String) -> Unit = {},
    onPlayPodcastEpisode: (HomeShelfItem.PodcastEpisode, List<HomeShelfItem.PodcastEpisode>, String) -> Unit = { _, _, _ -> },
    onHeaderLongClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (shelf.items.isEmpty()) return
    if (com.lastwave.app.data.music.isDailyDiscoverShelf(shelf) ||
        shelf.items.any { com.lastwave.app.data.music.isCommunityPlaylistItem(it) }) {
        CommunityDiscoverShelf(
            shelf = shelf, onPlayTrack = onPlayTrack, onPlayTracksQueue = onPlayTracksQueue,
            onPlayPlaylist = onPlayPlaylist, onOpenPlaylist = onOpenFeedPlaylist,
            onTrackMenu = onTrackMenu,
            onTrackLongClick = { onTrackLongClick(trackTray(it, shelf.title, onPlayTrack)) },
            modifier = modifier,
        ) { item ->
            NativeShelfItemCard(
                shelfItem = item,
                shelfTitle = shelf.title,
                onPlayTrack = onPlayTrack,
                onOpenFeedPlaylist = onOpenFeedPlaylist,
                onOpenAlbum = onOpenAlbum,
                onTrackLongClick = onTrackLongClick,
                onOpenPodcastShow = onOpenPodcastShow,
            )
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        val tracksInShelf = remember(shelf.items) {
            shelf.items.mapNotNull { (it as? HomeShelfItem.Track)?.track }
        }
        val episodesInShelf = remember(shelf.items) {
            shelf.items.filterIsInstance<HomeShelfItem.PodcastEpisode>()
        }
        // "More" only when the shelf's destination is a playlist/album id
        // the app already knows how to open; mood/genre/chart browse ids
        // aren't supported surfaces, so the chevron stays hidden for them.
        val morePlaylistId = remember(shelf.moreBrowseId) {
            shelf.moreBrowseId
                ?.removePrefix("VL")
                ?.takeIf { it.startsWith("PL") || it.startsWith("MPRE") || it.startsWith("RD") || it.startsWith("OLAK") }
        }
        com.lastwave.app.ui.feed.cards.ShelfHeaderV2(
            title = shelf.title,
            eyebrow = shelf.strapline,
            subtitle = shelf.subtitle,
            showMore = morePlaylistId != null,
            onMoreClick = morePlaylistId?.let { { onOpenFeedPlaylist(it) } },
            onShuffle = if (tracksInShelf.isNotEmpty()) {
                { onShuffleTracksQueue(tracksInShelf, shelf.title) }
            } else null,
            onLongClick = onHeaderLongClick?.let { { it(shelf.title) } },
        )
            FeedMediaRow(
                content = {
                    itemsIndexed(
                        shelf.items,
                        key = { _, item ->
                            when (item) {
                                is HomeShelfItem.Track -> "t_${item.track.videoId}"
                                is HomeShelfItem.Playlist -> "p_${item.playlist.id}"
                                is HomeShelfItem.PodcastEpisode -> "e_${item.videoId}"
                                is HomeShelfItem.PodcastShow -> "s_${item.browseId}"
                            }
                        },
                        contentType = { _, item -> item::class.java.simpleName },
                    ) { _, shelfItem ->
                        Box(Modifier.animateItem()) {
                            NativeShelfItemCard(
                                shelfItem = shelfItem,
                                shelfTitle = shelf.title,
                                onPlayTrack = onPlayTrack,
                                onOpenFeedPlaylist = onOpenFeedPlaylist,
                                onOpenAlbum = onOpenAlbum,
                                onTrackLongClick = onTrackLongClick,
                                onOpenPodcastShow = onOpenPodcastShow,
                                onTrackMenu = onTrackMenu,
                                onPlayPodcastEpisode = { ep -> onPlayPodcastEpisode(ep, episodesInShelf, shelf.title) },
                            )
                        }
                    }
                },
            )
        }
}

/**
 * Dispatches a single shelf item to its card variant based on the real YTM
 * data (aspect ratio, caption, community preview songs). Unknown shapes fall
 * back to the square card. Calls the same play/open/menu handlers as before.
 */
@Composable
private fun NativeShelfItemCard(
    shelfItem: HomeShelfItem,
    shelfTitle: String,
    onPlayTrack: (YouTubeMusicTrack, String) -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String) -> Unit,
    onTrackLongClick: (TrackMiniTrayData) -> Unit,
    onOpenPodcastShow: (String) -> Unit = {},
    onTrackMenu: (YouTubeMusicTrack) -> Unit = {},
    onPlayPodcastEpisode: ((HomeShelfItem.PodcastEpisode) -> Unit)? = null,
) {
    when (val variant = com.lastwave.app.ui.feed.cards.selectCardVariant(shelfItem)) {
        com.lastwave.app.ui.feed.cards.HomeCardVariant.PODCAST_EPISODE -> PodcastEpisodeItemCard(
            shelfItem = shelfItem,
            shelfTitle = shelfTitle,
            onPlayTrack = onPlayTrack,
            onTrackLongClick = onTrackLongClick,
            onTrackMenu = onTrackMenu,
            onPlayPodcastEpisode = onPlayPodcastEpisode,
        )
        com.lastwave.app.ui.feed.cards.HomeCardVariant.WIDE -> WideItemCard(
            shelfItem = shelfItem,
            shelfTitle = shelfTitle,
            onPlayTrack = onPlayTrack,
            onOpenFeedPlaylist = onOpenFeedPlaylist,
            onOpenAlbum = onOpenAlbum,
            onTrackLongClick = onTrackLongClick,
            onOpenPodcastShow = onOpenPodcastShow,
        )
        com.lastwave.app.ui.feed.cards.HomeCardVariant.FEATURED -> FeaturedItemCard(
            shelfItem = shelfItem,
            shelfTitle = shelfTitle,
            onPlayTrack = onPlayTrack,
            onOpenFeedPlaylist = onOpenFeedPlaylist,
            onOpenAlbum = onOpenAlbum,
            onTrackLongClick = onTrackLongClick,
            onOpenPodcastShow = onOpenPodcastShow,
        )
        com.lastwave.app.ui.feed.cards.HomeCardVariant.COMMUNITY -> {
            val item = shelfItem as? HomeShelfItem.Playlist ?: return
            CommunityItemCard(
                item = item,
                shelfTitle = shelfTitle,
                onPlayTrack = onPlayTrack,
                onOpenFeedPlaylist = onOpenFeedPlaylist,
                onOpenAlbum = onOpenAlbum,
                onTrackLongClick = onTrackLongClick,
            )
        }
        else -> SquareItemCard(
            shelfItem = shelfItem,
            shelfTitle = shelfTitle,
            onPlayTrack = onPlayTrack,
            onOpenFeedPlaylist = onOpenFeedPlaylist,
            onOpenAlbum = onOpenAlbum,
            onTrackLongClick = onTrackLongClick,
            onOpenPodcastShow = onOpenPodcastShow,
        )
    }
}

@Composable
private fun PodcastEpisodeItemCard(
    shelfItem: HomeShelfItem,
    shelfTitle: String,
    onPlayTrack: (YouTubeMusicTrack, String) -> Unit,
    onTrackLongClick: (TrackMiniTrayData) -> Unit,
    onTrackMenu: (YouTubeMusicTrack) -> Unit = {},
    onPlayPodcastEpisode: ((HomeShelfItem.PodcastEpisode) -> Unit)? = null,
) {
    val ep = shelfItem as? HomeShelfItem.PodcastEpisode ?: return
    val track = ep.toYouTubeMusicTrack()
    val infoLine = listOfNotNull(
        ep.subtitle?.takeIf { it.isNotBlank() },
        ep.showName?.takeIf { it.isNotBlank() },
    ).joinToString(" \u2022 ")

    com.lastwave.app.ui.feed.cards.ShelfPodcastEpisodeCard(
        title = ep.title,
        infoLine = infoLine,
        artworkUrl = ep.artworkUrl,
        durationText = ep.durationText,
        progressPercentage = ep.progressFraction,
        onClick = {
            if (onPlayPodcastEpisode != null) {
                onPlayPodcastEpisode(ep)
            } else {
                onPlayTrack(track, shelfTitle)
            }
        },
        onLongClick = { onTrackLongClick(trackTray(track, shelfTitle, onPlayTrack)) },
        onMenuClick = { onTrackMenu(track) },
    )
}

/**
 * Short glass empty state displayed when the Podcasts chip has no shelves (e.g. unauthenticated guest).
 */
@Composable
private fun PodcastGuestEmptyState(
    onLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "Discover personalized podcasts and shows",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(
            onClick = onLoginClick,
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("Log in to YouTube Music")
        }
    }
}

/** Builds the track mini-tray payload used by every card's long-press. */
private fun trackTray(track: YouTubeMusicTrack, shelfTitle: String, onPlayTrack: (YouTubeMusicTrack, String) -> Unit) =
    TrackMiniTrayData(
        title = track.title,
        artist = track.artist,
        album = track.album,
        artworkUrl = track.artworkUrl,
        videoId = track.videoId.takeIf(String::isNotBlank),
        sourceLabel = "Home",
        onPlay = { onPlayTrack(track, shelfTitle) },
    )

private fun playlistInfoLine(p: com.lastwave.app.data.music.YouTubePlaylistSummary): String =
    listOfNotNull(
        p.author,
        p.trackCountText,
    ).joinToString(" \u2022 ").ifBlank { p.author.orEmpty() }

private fun openPlaylistOrAlbum(
    playlist: com.lastwave.app.data.music.YouTubePlaylistSummary,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String) -> Unit,
) {
    if (playlist.id.startsWith("MPRE")) {
        onOpenAlbum(playlist.title, playlist.author.orEmpty(), playlist.id)
    } else {
        onOpenFeedPlaylist(playlist.id)
    }
}

@Composable
private fun SquareItemCard(
    shelfItem: HomeShelfItem,
    shelfTitle: String,
    onPlayTrack: (YouTubeMusicTrack, String) -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String) -> Unit,
    onTrackLongClick: (TrackMiniTrayData) -> Unit,
    onOpenPodcastShow: (String) -> Unit = {},
) {
    when (shelfItem) {
        is HomeShelfItem.Track -> {
            val track = shelfItem.track
            com.lastwave.app.ui.feed.cards.ShelfSquareCard(
                title = track.title,
                infoLine = ArtistHelper.primaryArtist(track.artist),
                artworkUrl = track.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                onClick = { onPlayTrack(track, shelfTitle) },
                onLongClick = { onTrackLongClick(trackTray(track, shelfTitle, onPlayTrack)) },
                onPlayClick = { onPlayTrack(track, shelfTitle) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberSquareCardWidth(),
            )
        }
        is HomeShelfItem.Playlist -> {
            val p = shelfItem.playlist
            com.lastwave.app.ui.feed.cards.ShelfSquareCard(
                title = p.title,
                infoLine = playlistInfoLine(p),
                artworkUrl = p.artworkUrl,
                fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                onClick = { openPlaylistOrAlbum(p, onOpenFeedPlaylist, onOpenAlbum) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberSquareCardWidth(),
            )
        }
        is HomeShelfItem.PodcastShow -> {
            val show = shelfItem
            com.lastwave.app.ui.feed.cards.ShelfSquareCard(
                title = show.title,
                infoLine = show.author.orEmpty(),
                artworkUrl = show.artworkUrl,
                fallbackIcon = Icons.Filled.Mic,
                onClick = { onOpenPodcastShow(show.browseId) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberSquareCardWidth(),
            )
        }
        is HomeShelfItem.PodcastEpisode -> {
            val ep = shelfItem
            com.lastwave.app.ui.feed.cards.ShelfSquareCard(
                title = ep.title,
                infoLine = listOfNotNull(ep.subtitle, ep.showName).joinToString(" \u2022 "),
                artworkUrl = ep.artworkUrl,
                fallbackIcon = Icons.Filled.Mic,
                onClick = { onPlayTrack(ep.toYouTubeMusicTrack(), shelfTitle) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberSquareCardWidth(),
            )
        }
    }
}

@Composable
private fun WideItemCard(
    shelfItem: HomeShelfItem,
    shelfTitle: String,
    onPlayTrack: (YouTubeMusicTrack, String) -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String) -> Unit,
    onTrackLongClick: (TrackMiniTrayData) -> Unit,
    onOpenPodcastShow: (String) -> Unit = {},
) {
    when (shelfItem) {
        is HomeShelfItem.Track -> {
            val track = shelfItem.track
            com.lastwave.app.ui.feed.cards.ShelfWideCard(
                title = track.title,
                infoLine = ArtistHelper.primaryArtist(track.artist),
                artworkUrl = track.artworkUrl,
                onClick = { onPlayTrack(track, shelfTitle) },
                onLongClick = { onTrackLongClick(trackTray(track, shelfTitle, onPlayTrack)) },
                onPlayClick = { onPlayTrack(track, shelfTitle) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberWideCardWidth(),
            )
        }
        is HomeShelfItem.Playlist -> {
            val p = shelfItem.playlist
            com.lastwave.app.ui.feed.cards.ShelfWideCard(
                title = p.title,
                infoLine = playlistInfoLine(p),
                artworkUrl = p.artworkUrl,
                fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                onClick = { openPlaylistOrAlbum(p, onOpenFeedPlaylist, onOpenAlbum) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberWideCardWidth(),
            )
        }
        is HomeShelfItem.PodcastShow -> {
            val show = shelfItem
            com.lastwave.app.ui.feed.cards.ShelfWideCard(
                title = show.title,
                infoLine = show.author.orEmpty(),
                artworkUrl = show.artworkUrl,
                onClick = { onOpenPodcastShow(show.browseId) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberWideCardWidth(),
            )
        }
        is HomeShelfItem.PodcastEpisode -> {
            val ep = shelfItem
            com.lastwave.app.ui.feed.cards.ShelfWideCard(
                title = ep.title,
                infoLine = listOfNotNull(ep.subtitle, ep.showName).joinToString(" \u2022 "),
                artworkUrl = ep.artworkUrl,
                onClick = { onPlayTrack(ep.toYouTubeMusicTrack(), shelfTitle) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberWideCardWidth(),
            )
        }
    }
}

@Composable
private fun FeaturedItemCard(
    shelfItem: HomeShelfItem,
    shelfTitle: String,
    onPlayTrack: (YouTubeMusicTrack, String) -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String) -> Unit,
    onTrackLongClick: (TrackMiniTrayData) -> Unit,
    onOpenPodcastShow: (String) -> Unit = {},
) {
    when (shelfItem) {
        is HomeShelfItem.Track -> {
            val track = shelfItem.track
            com.lastwave.app.ui.feed.cards.ShelfFeaturedCard(
                title = track.title,
                artist = ArtistHelper.primaryArtist(track.artist),
                artworkUrl = track.artworkUrl,
                caption = shelfItem.caption,
                onClick = { onPlayTrack(track, shelfTitle) },
                onLongClick = { onTrackLongClick(trackTray(track, shelfTitle, onPlayTrack)) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberFeaturedCardWidth(),
            )
        }
        is HomeShelfItem.Playlist -> {
            val p = shelfItem.playlist
            com.lastwave.app.ui.feed.cards.ShelfFeaturedCard(
                title = p.title,
                artist = playlistInfoLine(p),
                artworkUrl = p.artworkUrl,
                caption = shelfItem.caption,
                fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                onClick = { openPlaylistOrAlbum(p, onOpenFeedPlaylist, onOpenAlbum) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberFeaturedCardWidth(),
            )
        }
        is HomeShelfItem.PodcastShow -> {
            val show = shelfItem
            com.lastwave.app.ui.feed.cards.ShelfFeaturedCard(
                title = show.title,
                artist = show.author.orEmpty(),
                artworkUrl = show.artworkUrl,
                caption = shelfItem.caption,
                onClick = { onOpenPodcastShow(show.browseId) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberFeaturedCardWidth(),
            )
        }
        is HomeShelfItem.PodcastEpisode -> {
            val ep = shelfItem
            com.lastwave.app.ui.feed.cards.ShelfFeaturedCard(
                title = ep.title,
                artist = listOfNotNull(ep.subtitle, ep.showName).joinToString(" \u2022 "),
                artworkUrl = ep.artworkUrl,
                caption = shelfItem.caption,
                onClick = { onPlayTrack(ep.toYouTubeMusicTrack(), shelfTitle) },
                cardWidth = com.lastwave.app.ui.feed.cards.rememberFeaturedCardWidth(),
            )
        }
    }
}

@Composable
private fun CommunityItemCard(
    item: HomeShelfItem.Playlist,
    shelfTitle: String,
    onPlayTrack: (YouTubeMusicTrack, String) -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String) -> Unit,
    onTrackLongClick: (TrackMiniTrayData) -> Unit,
) {
    val p = item.playlist
    com.lastwave.app.ui.feed.cards.ShelfCommunityPlaylistCard(
        playlistId = p.id,
        title = p.title,
        subtitle = playlistInfoLine(p),
        collageUrls = item.collageArtworkUrls,
        songs = item.previewSongs,
        artworkUrl = p.artworkUrl,
        onClick = { openPlaylistOrAlbum(p, onOpenFeedPlaylist, onOpenAlbum) },
        onPlayClick = {
            if (item.previewSongs.isNotEmpty()) {
                onPlayTrack(item.previewSongs.first(), p.title)
            } else {
                openPlaylistOrAlbum(p, onOpenFeedPlaylist, onOpenAlbum)
            }
        },
        onSongClick = { onPlayTrack(it, p.title) },
        onSongMenuClick = { onTrackLongClick(trackTray(it, p.title, onPlayTrack)) },
        cardWidth = com.lastwave.app.ui.feed.cards.rememberCommunityCardWidth(),
    )
}

@Composable
private fun FeedLoadingSkeleton() {
    val transition = rememberInfiniteTransition(label = "feedSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "skeletonPulse",
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = FloatingNavDefaults.contentBottomPadding(),
            top = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(208.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f * pulse + 0.3f)),
            )
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f * pulse + 0.3f)),
                            )
                        }
                    }
                }
            }
        }
        items(3) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .width((140 + row * 30).dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f * pulse + 0.3f)),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false,
                ) {
                    items(4) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(148.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f * pulse + 0.3f)),
                            )
                            Box(
                                modifier = Modifier
                                    .width(110.dp)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f * pulse + 0.3f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedFooter(lastUpdatedMillis: Long) {
    val context = LocalContext.current
    val label = remember(lastUpdatedMillis) {
        if (lastUpdatedMillis <= 0L) context.getString(R.string.feed_footer_default)
        else try {
            val time = java.time.Instant.ofEpochMilli(lastUpdatedMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
            context.getString(R.string.feed_footer_updated, time)
        } catch (_: Exception) {
            context.getString(R.string.feed_footer_default)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}



@Composable
private fun QuickTilesGrid(
    tiles: List<FeedQuickTile>,
    onTileClick: (FeedQuickTile) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = tiles,
            key = { "tile_${it.title}_${it.collection}" },
            contentType = { "quick_tile" },
        ) { tile ->
            QuickTileCard(
                tile = tile,
                onClick = { onTileClick(tile) },
            )
        }
    }
}

@Composable
private fun QuickTileCard(
    tile: FeedQuickTile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tileShape = RoundedCornerShape(18.dp)

    // Per-type vibrant gradient pairs for the artwork/icon box matching modern expressive designs
    val isLiveMapTile = tile.collection == "live_map"
    val isYtLikedTile = tile.collection == "yt_liked" || tile.playlistId == "yt_liked"
    val isLocalLikedTile = tile.isLiked && !isYtLikedTile
    val isLikedTile = isYtLikedTile || isLocalLikedTile
    val isMixTile = tile.collection == "mix" || tile.collection == "discover_mix" || tile.title.contains("Discover Mix", ignoreCase = true)
    val isMyMixTile = tile.collection == "my_mix" || tile.title.equals("My Mix", ignoreCase = true)
    val isDiscoverTile = tile.collection == "radio" || tile.title.equals("Discover", ignoreCase = true)
    val isNewReleasesTile = tile.collection == "new_releases"

    val iconGradient = when {
        isLiveMapTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary),
        )
        isLikedTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary),
        )
        isMixTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary),
        )
        isMyMixTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
        isDiscoverTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary),
        )
        isNewReleasesTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error),
        )
        else -> Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }

    Surface(
        onClick = onClick,
        shape = tileShape,
        color = when {
            isLiveMapTile -> MaterialTheme.colorScheme.tertiaryContainer
            isLikedTile -> MaterialTheme.colorScheme.primaryContainer
            isMixTile -> MaterialTheme.colorScheme.secondaryContainer
            isMyMixTile -> MaterialTheme.colorScheme.surfaceVariant
            isDiscoverTile -> MaterialTheme.colorScheme.tertiaryContainer
            isNewReleasesTile -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.10f),
        ),
        modifier = modifier
            .width(136.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconGradient),
                contentAlignment = Alignment.Center,
            ) {
                if (isLiveMapTile) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isYtLikedTile) {
                    Icon(
                        Icons.Filled.ThumbUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isLocalLikedTile) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isMixTile) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isMyMixTile) {
                    Icon(
                        Icons.Filled.AllInclusive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isDiscoverTile) {
                    Icon(
                        Icons.Filled.Explore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isNewReleasesTile) {
                    Icon(
                        Icons.Filled.NewReleases,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else {
                    ArtworkImage(
                        name = tile.title,
                        artist = tile.subtitle ?: "",
                        embeddedUrl = tile.artworkUrl,
                        fallbackIcon = Icons.Filled.Album,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 2.dp, end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = tile.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val finalSubtitle = if (isNewReleasesTile) "Fresh drops" else tile.subtitle ?: if (tile.actionVideoId != null) "Track" else "Playlist"
                if (finalSubtitle.isNotEmpty()) {
                    Text(
                        text = finalSubtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPicksRows(
    tracks: List<YouTubeMusicTrack>,
    currentPlayingVideoId: String?,
    isPlaying: Boolean,
    onTrackClick: (Int) -> Unit,
    onMenuClick: (YouTubeMusicTrack) -> Unit,
    onLongClick: (YouTubeMusicTrack, Int) -> Unit = { _, _ -> },
) {
    val sizeClass = rememberWindowSizeClass()
    val haptics = LocalHapticFeedback.current
    val widthFraction = when (sizeClass) {
        WindowSizeClass.COMPACT -> 0.88f
        WindowSizeClass.MEDIUM -> 0.48f
        WindowSizeClass.EXPANDED -> 0.32f
    }
    val columns = remember(tracks) { tracks.chunked(3) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        itemsIndexed(columns) { columnIndex, column ->
            Column(
                modifier = Modifier.fillParentMaxWidth(widthFraction),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                column.forEachIndexed { rowIndex, track ->
                    val overallIndex = columnIndex * 3 + rowIndex
                    val isCurrent = track.videoId.isNotBlank() && track.videoId == currentPlayingVideoId
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else Color.Transparent,
                        border = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onTrackClick(overallIndex) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongClick(track, overallIndex)
                                },
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(modifier = Modifier.size(48.dp)) {
                                ArtworkImage(
                                    name = track.title,
                                    artist = ArtistHelper.primaryArtist(track.artist),
                                    embeddedUrl = track.artworkUrl,
                                    fallbackIcon = Icons.Filled.MusicNote,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                )
                                if (isCurrent && isPlaying) {
                                    PlayingWaveBars(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(2.dp)
                                            .size(24.dp, 18.dp),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                                Text(
                                    ArtistHelper.primaryArtist(track.artist),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                            IconButton(
                                onClick = { onMenuClick(track) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.feed_more_cd, track.title),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedMediaRow(
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
        modifier = Modifier.padding(top = 10.dp),
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedMediaCard(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    fallbackIcon: ImageVector,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    badgeText: String? = null,
    cardWidth: androidx.compose.ui.unit.Dp = rememberSquareCardWidth(),
) {
    val haptics = LocalHapticFeedback.current
    val coverHeight = rememberCoverHeight()
    Column(
        modifier = Modifier
            .width(cardWidth)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
    ) {
        Box(modifier = Modifier.width(cardWidth).height(coverHeight)) {
            // No onClick here on purpose: the outer Column's combinedClickable
            // owns tap + long-press for the whole card. An inner clickable
            // Surface would swallow the press and starve the long-press tray.
            Surface(
                shape = RoundedCornerShape(CardTokens.coverCornerRadius),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    ArtworkImage(
                        name = title,
                        artist = subtitle,
                        embeddedUrl = artworkUrl,
                        fallbackIcon = fallbackIcon,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (badgeText != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        ) {
                            Text(
                                badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(CardTokens.coverToTextGap))
        CardTextArea(title = title, infoLine = subtitle)
    }
}

@Composable
private fun FeedPlaylistCard(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    onPlayClick: (() -> Unit)? = null,
) {
    FeedMediaCard(
        title = title,
        subtitle = subtitle,
        artworkUrl = artworkUrl,
        fallbackIcon = Icons.Filled.Album,
        onClick = onClick,
        onPlayClick = onPlayClick,
        cardWidth = rememberSquareCardWidth(),
    )
}

// Legacy card variants were consolidated into FeedMediaCard / FeedPlaylistCard
// so every shelf shares one artwork, type and play-affordance language.

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentTrackCard(
    track: RecentTrack,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val ago = remember(track.date?.uts, track.url) { relativeTime(context, track.date?.uts) }
    val primaryArtistName = remember(track.artist.displayName) { ArtistHelper.primaryArtist(track.artist.displayName) }
    val subtitle = if (ago != null) "$primaryArtistName \u2022 $ago" else primaryArtistName
    val haptics = LocalHapticFeedback.current
    val cardWidth = rememberSquareCardWidth()
    val coverHeight = rememberCoverHeight()
    Column(
        modifier = Modifier
            .width(cardWidth)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            ),
    ) {
        Surface(
            shape = RoundedCornerShape(CardTokens.coverCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
            modifier = Modifier.width(cardWidth).height(coverHeight),
        ) {
            Box(Modifier.fillMaxSize()) {
                ArtworkImage(
                    name = track.name,
                    artist = primaryArtistName,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize(),
                )
                if (ago != null) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.62f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    ) {
                        Text(
                            ago,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(CardTokens.coverToTextGap))
        CardTextArea(title = track.name, infoLine = subtitle)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChartTrackCard(
    rank: Int,
    track: YouTubeMusicTrack,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val isTop3 = rank <= 3
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isTop3) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else Color.Transparent,
        border = null,
        modifier = Modifier
            .width(288.dp)
            .height(76.dp)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = String.format("%02d", rank),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                fontWeight = FontWeight.Black,
                color = if (isTop3) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.width(40.dp),
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(56.dp),
            ) {
                Box(Modifier.fillMaxSize()) {
                    ArtworkImage(
                        name = track.title,
                        artist = ArtistHelper.primaryArtist(track.artist),
                        embeddedUrl = track.artworkUrl,
                        fallbackIcon = Icons.Filled.TrendingUp,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = ArtistHelper.primaryArtist(track.artist),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun SpotlightHeroCard(
    spotlight: FeedSpotlight,
    onPlayRadio: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    val primaryArtistName = remember(spotlight.artistName) { ArtistHelper.primaryArtist(spotlight.artistName) }
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (!spotlight.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = spotlight.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .alpha(0.28f),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gradientBrush)
                    .padding(20.dp),
            ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            "ARTIST SPOTLIGHT",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        onClick = onOpenArtist,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(76.dp),
                    ) {
                        if (!spotlight.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = spotlight.artworkUrl,
                                contentDescription = stringResource(R.string.feed_open_artist_cd, primaryArtistName),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    primaryArtistName.take(1).uppercase(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            primaryArtistName,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                        spotlight.topTrackTitle?.takeIf(String::isNotBlank)?.let { title ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPlayRadio,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.feed_artist_radio), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Surface(
                        onClick = onOpenArtist,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "View artist",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ArtistAvatarCard(
    artist: FeedArtist,
    onClick: () -> Unit,
    isTop: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val primaryArtistName = remember(artist.name) { ArtistHelper.primaryArtist(artist.name) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(100.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
    ) {
        Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (isTop) androidx.compose.foundation.BorderStroke(
                    2.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                ) else androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                ),
                shadowElevation = if (isTop) 6.dp else 0.dp,
                modifier = Modifier.size(88.dp),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ) {
                if (!artist.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artist.artworkUrl,
                        contentDescription = primaryArtistName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = primaryArtistName.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (isTop) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Icon(
                        Icons.Filled.Whatshot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(4.dp).size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = primaryArtistName,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun FriendAvatarCard(
    friend: FriendEntry,
    onClick: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(60.dp),
        ) {
            if (!friend.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = friend.avatarUrl,
                    contentDescription = friend.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = friend.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = friend.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun HomeContainerSection(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(0.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 3.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            content = content,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FeedSectionHeader(
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
    onShuffleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onActionClick?.invoke() },
                    onLongClick = onLongClick?.let {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            it()
                        }
                    },
                ),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, letterSpacing = (-0.4).sp),
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(start = 2.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        if (onShuffleClick != null) {
            ExpressiveShuffleButton(
                onClick = onShuffleClick,
                contentDescription = stringResource(R.string.feed_shuffle_title_cd, title),
            )
        }
        if (actionText != null && onActionClick != null && actionText != stringResource(R.string.feed_play_all)) {
            Surface(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onActionClick()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    actionIcon?.let { icon ->
                        Icon(
                            if (icon == Icons.Filled.PlayArrow) Icons.Filled.PlayCircle else icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Text(
                        actionText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedEmptyState(
    message: String,
    onRetry: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeHorizontalContentPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = FloatingNavDefaults.contentBottomPadding())
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            modifier = Modifier.size(72.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Find your next favorite",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onOpenSearch,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.feed_search_music), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Surface(
                onClick = onRetry,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                modifier = Modifier,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Try again",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class ArtistAlbumNavBridgeFeed @javax.inject.Inject constructor(val navigator: ArtistAlbumNavigator) : androidx.lifecycle.ViewModel()
