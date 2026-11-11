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
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
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
import androidx.compose.ui.layout.onSizeChanged
import com.lastwave.app.ui.theme.liquidGlassSource
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import com.lastwave.app.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.ui.feed.cards.CardTokens
import com.lastwave.app.ui.feed.cards.CardTextArea
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
import com.lastwave.app.data.local.DEFAULT_SECTION_ORDER
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
    val playbackState by musicPlayer.listPlaybackState.collectAsStateWithLifecycle()
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
                scrollOffsetY = { podcastHeroScrollOffset },
            )
        }

        // Liquid glass: the feed scrolls *under* the header so the glass has
        // real content to frost and refract. Previously the header sat above
        // the list in a Column and sampled only its own flat background, so
        // it read as a tinted bar. The list is the backdrop source and the
        // header a sibling on top of it (never inside it: no self-capture).
        // Without glass (or on the transparent Podcasts hero) the list keeps
        // starting below the header exactly as before.
        val feedGlass = com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported() && !isPodcastsActive
        val feedBackgroundColor = MaterialTheme.colorScheme.background
        val feedBackdrop = com.lastwave.app.ui.theme.rememberLayerBackdrop {
            drawRect(feedBackgroundColor)
            drawContent()
        }
        val useFeedGlass = feedGlass && feedBackdrop != null
        var topBarHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val topBarHeight = with(androidx.compose.ui.platform.LocalDensity.current) { topBarHeightPx.toDp() }
        val listTopInset = if (useFeedGlass) topBarHeight else 0.dp
        val feedCornerDensity = androidx.compose.ui.platform.LocalDensity.current
        val feedCornerInsetPx = with(feedCornerDensity) { 16.dp.toPx() }
        val feedCornerRadiusPx = with(feedCornerDensity) { 28.dp.toPx() }
        val feedCornerTopPx = with(feedCornerDensity) { listTopInset.toPx() }
        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 920.dp),
        ) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                state = pullState,
                indicator = {
                    androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.isRefreshing,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = listTopInset),
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (useFeedGlass) 0.dp else topBarHeight)
                    .then(
                        if (useFeedGlass && feedBackdrop != null) {
                            Modifier.liquidGlassSource(feedBackdrop)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (state.isLoading) {
                Box(Modifier.padding(top = listTopInset)) { FeedLoadingSkeleton() }
            } else if (!hasFeedContent) {
                Box(Modifier.padding(top = listTopInset)) {
                FeedEmptyState(
                    message = if (state.error != null) {
                        "We couldn't load your recommendations. Try again, or find something in search."
                    } else {
                        "Search for a favorite or explore something new. Your music starts here."
                    },
                    onRetry = viewModel::loadFeed,
                    onOpenSearch = onOpenSearch,
                )
                }
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

                val allHiddenIds = remember(hiddenSections, ytHidden) {
                    hiddenSections + ytHidden.map { "yt:$it" }.toSet()
                }

                fun isShelfTitleHidden(title: String): Boolean {
                    val t = title.trim().lowercase()
                    if (t.isBlank()) return true
                    if ("yt:$title" in allHiddenIds || title in ytHidden || "yt:$t" in allHiddenIds || t in ytHidden) return true

                    // Check specific mapped toggles:
                    if (("new_releases" in hiddenSections || "yt:New releases" in allHiddenIds || ytHidden.any { it.contains("new release", ignoreCase = true) }) &&
                        t.contains("new release")
                    ) return true

                    if (("yt:Music video for you" in allHiddenIds || ytHidden.any { it.contains("music video", ignoreCase = true) }) &&
                        (t.contains("music video") || t.contains("music videos"))
                    ) return true

                    if (("mixed_for_you" in hiddenSections || "yt:Mixes to explore" in allHiddenIds || ytHidden.any { it.contains("mix", ignoreCase = true) }) &&
                        (t.contains("mixed for you") || t.contains("mixes for you") || t.contains("mixes to explore") || t.contains("mixes") || t.contains("mix"))
                    ) return true

                    if (("trending_charts" in hiddenSections || "yt:Trending songs for you" in allHiddenIds || ytHidden.any { it.contains("trend", ignoreCase = true) }) &&
                        (t.contains("trend") || t.contains("chart"))
                    ) return true

                    if (("because_you_listen_to" in hiddenSections || "yt:Similar to" in allHiddenIds || ytHidden.any { it.contains("similar", ignoreCase = true) }) &&
                        (t.contains("similar to") || t.contains("because you"))
                    ) return true

                    if (("jump_back_in" in hiddenSections || "yt:Listen again" in allHiddenIds || ytHidden.any { it.contains("listen again", ignoreCase = true) }) &&
                        (t.contains("listen again") || t.contains("jump back"))
                    ) return true

                    if (("quick_picks" in hiddenSections || ytHidden.any { it.contains("quick pick", ignoreCase = true) || it.contains("picked for you", ignoreCase = true) }) &&
                        (t.contains("quick pick") || t.contains("picked for you"))
                    ) return true

                    if (("albums_in_rotation" in hiddenSections || "yt:Album for you" in allHiddenIds || "yt:Albums for you" in allHiddenIds || ytHidden.any { it.contains("album", ignoreCase = true) }) &&
                        (t.contains("album for you") || t.contains("albums for you"))
                    ) return true

                    if (("yt:From the community" in allHiddenIds || ytHidden.any { it.contains("community", ignoreCase = true) }) &&
                        t.contains("community")
                    ) return true

                    if (("yt:Forgotten favorites" in allHiddenIds || ytHidden.any { it.contains("forgotten", ignoreCase = true) }) &&
                        t.contains("forgotten")
                    ) return true

                    if (("yt:From your library" in allHiddenIds || ytHidden.any { it.contains("library", ignoreCase = true) }) &&
                        t.contains("library")
                    ) return true

                    if (("yt:Heard in shorts" in allHiddenIds || ytHidden.any { it.contains("short", ignoreCase = true) }) &&
                        t.contains("short")
                    ) return true

                    if (("yt:Live performances" in allHiddenIds || ytHidden.any { it.contains("live", ignoreCase = true) }) &&
                        t.contains("live performance")
                    ) return true

                    if (("yt:Cover and remixes" in allHiddenIds || ytHidden.any { it.contains("cover", ignoreCase = true) || it.contains("remix", ignoreCase = true) }) &&
                        (t.contains("cover") || t.contains("remix"))
                    ) return true

                    if (("podcast_shelf" in hiddenSections || "podcasts" in hiddenSections) &&
                        t.contains("podcast")
                    ) return true

                    return ytHidden.any { h ->
                        val hl = h.trim().lowercase()
                        t == hl || t.startsWith(hl) || hl.startsWith(t)
                    }
                }

                fun isSectionIdHidden(id: String): Boolean {
                    if (id == "hero_greeting" || id == "infinite_radio" || id == "quick_tiles" || id == "hero") return false
                    if (id in allHiddenIds || id in hiddenSections) return true
                    if (id == "new_releases" && ("new_releases" in hiddenSections || "yt:New releases" in allHiddenIds)) return true
                    if (id.startsWith("yt:")) {
                        val ytTitle = id.removePrefix("yt:").trim()
                        return isShelfTitleHidden(ytTitle)
                    }
                    return false
                }

                // Deduplicate / merge duplicate sections:
                val ytNewReleasesShelf = remember(state.feedData.nativeShelves) {
                    state.feedData.nativeShelves.firstOrNull {
                        it.title.contains("new release", ignoreCase = true)
                    }
                }
                val combinedNewReleases = remember(state.feedData.newReleases, ytNewReleasesShelf) {
                    val fromYt = ytNewReleasesShelf?.items?.mapNotNull { item ->
                        when (item) {
                            is HomeShelfItem.Playlist -> item.playlist
                            is HomeShelfItem.Track -> YouTubePlaylistSummary(
                                id = item.track.videoId,
                                title = item.track.title,
                                author = item.track.artist,
                                trackCountText = null,
                                artworkUrl = item.track.artworkUrl,
                            )
                            else -> null
                        }
                    }.orEmpty()
                    (state.feedData.newReleases + fromYt).distinctBy { it.id.ifBlank { it.title } }
                }

                val ytQuickPicksShelf = remember(state.feedData.nativeShelves) {
                    state.feedData.nativeShelves.firstOrNull {
                        it.title.contains("quick pick", ignoreCase = true) || it.title.contains("picked for you", ignoreCase = true)
                    }
                }
                val combinedQuickPicks = remember(state.feedData.quickPicks, ytQuickPicksShelf) {
                    val fromYt = ytQuickPicksShelf?.items?.filterIsInstance<HomeShelfItem.Track>()?.map { it.track }.orEmpty()
                    (state.feedData.quickPicks + fromYt).distinctBy { it.videoId.ifBlank { it.title } }
                }

                val remainingNativeShelves = remember(state.feedData.nativeShelves, ytNewReleasesShelf, ytQuickPicksShelf, allHiddenIds, ytHidden) {
                    state.feedData.nativeShelves.filter { shelf ->
                        shelf !== ytNewReleasesShelf &&
                            shelf !== ytQuickPicksShelf &&
                            shelf.items.isNotEmpty() &&
                            !isSectionIdHidden("yt:${shelf.title}") &&
                            !isShelfTitleHidden(shelf.title)
                    }
                }

                val remainingShelfKeys = remember(remainingNativeShelves) {
                    remainingNativeShelves.map { "yt:${it.title}" }
                }
                // Maps a "yt:<title>" section key to its shelf. Blank titles are skipped,
                // otherwise the contains() match below would match everything.
                val resolveNativeShelf: (String) -> HomeShelf? = remember(remainingNativeShelves) {
                    val normalized = remainingNativeShelves
                        .map { it.title.trim().lowercase() to it }
                        .filter { (title, _) -> title.isNotEmpty() }
                    val byTitle = normalized.toMap()
                    val resolver: (String) -> HomeShelf? = { sectionKey ->
                        val target = sectionKey.removePrefix("yt:").trim().lowercase()
                        if (target.isEmpty()) {
                            null
                        } else {
                            byTitle[target] ?: normalized.firstOrNull { (title, _) ->
                                title.contains(target) || target.contains(title)
                            }?.second
                        }
                    }
                    resolver
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

                val displayTags = remember(topGenres, state.feedData.tasteTags) {
                    topGenres.ifEmpty {
                        state.feedData.tasteTags.ifEmpty {
                            listOf("Rock", "Indie", "Pop", "Electronic", "Hip-Hop", "R&B", "Synthwave", "Alternative")
                        }
                    }
                }

                val isSectionAvailable: (String) -> Boolean = { key ->
                    when {
                        key == "hero_greeting" || key == "infinite_radio" || key == "quick_tiles" -> false
                        key == "taste_strip" -> showSynthetic && displayTags.isNotEmpty()
                        key == "discover_new_music" -> showSynthetic && discoveryTracks.isNotEmpty()
                        key == "quick_picks" -> showSynthetic && combinedQuickPicks.isNotEmpty()
                        key == "because_you_listen_to" -> showSynthetic && state.feedData.becauseYouListenTo?.items?.isNotEmpty() == true
                        key == "fresh_finds" -> showSynthetic && state.feedData.freshFinds.isNotEmpty()
                        key == "jump_back_in" -> showSynthetic && state.feedData.jumpBackIn.isNotEmpty()
                        key == "mixed_for_you" || key == "mixes" -> showSynthetic && state.feedData.mixes.isNotEmpty()
                        key == "spotlight_hero" || key == "spotlight" -> showSynthetic && state.feedData.spotlight != null
                        key == "top_artists" -> showSynthetic && individualTopArtists.isNotEmpty()
                        key == "heavy_rotation" -> showSynthetic && state.feedData.heavyRotation.isNotEmpty()
                        key == "albums_in_rotation" || key == "albums" -> showSynthetic && state.feedData.recentAlbums.isNotEmpty()
                        key == "trending_charts" || key == "charts" -> showSynthetic && state.feedData.charts.isNotEmpty()
                        key == "new_releases" -> showSynthetic && combinedNewReleases.isNotEmpty()
                        key == "friends_activity" || key == "friends" -> state.feedData.friends.isNotEmpty()
                        key == "podcast_shelf" || key == "podcasts" -> showSynthetic && podcastsEnabled && state.feedData.podcastShows.isNotEmpty()
                        key.startsWith("yt:") -> resolveNativeShelf(key) != null
                        else -> false
                    }
                }

                val (orderedFeedSections, ytShelfBySectionKey) = remember(
                    showSynthetic, allHiddenIds, pinnedHomeSections, homeSectionsOrder,
                    quickTiles, podcastsEnabled, state.feedData.podcastShows, displayTags,
                    combinedQuickPicks, discoveryTracks, state.feedData.becauseYouListenTo,
                    state.feedData.freshFinds, state.feedData.jumpBackIn, state.feedData.mixes,
                    individualTopArtists, state.feedData.heavyRotation, state.feedData.recentAlbums,
                    state.feedData.charts, combinedNewReleases, state.feedData.friends,
                    remainingNativeShelves, resolveNativeShelf, state.feedData.spotlight
                ) {
                    if (state.isChipActive) return@remember emptyList<String>() to emptyMap<String, HomeShelf>()

                    // cache so each yt: key is resolved once
                    val ytResolved = HashMap<String, HomeShelf?>()
                    fun ytShelf(key: String): HomeShelf? =
                        if (key in ytResolved) ytResolved[key] else resolveNativeShelf(key).also { ytResolved[key] = it }

                    val allCandidateKeys = (DEFAULT_SECTION_ORDER + homeSectionsOrder + remainingShelfKeys).distinct()
                    val activeKeys = allCandidateKeys.filter { key ->
                        key != "hero_greeting" && key != "infinite_radio" && key != "quick_tiles" &&
                            !isSectionIdHidden(key) &&
                            (if (key.startsWith("yt:")) ytShelf(key) != null else isSectionAvailable(key))
                    }
                    val baseOrder = if (homeSectionsOrder.isNotEmpty()) homeSectionsOrder else DEFAULT_SECTION_ORDER
                    val sorted = activeKeys.sortedWith(compareBy { key ->
                        val exact = baseOrder.indexOf(key)
                        if (exact >= 0) exact else {
                            val ytIdx = baseOrder.indexOfFirst { bo ->
                                bo.equals(key, ignoreCase = true) ||
                                    (bo.startsWith("yt:") && key.startsWith("yt:") &&
                                     bo.removePrefix("yt:").trim().equals(key.removePrefix("yt:").trim(), ignoreCase = true))
                            }
                            if (ytIdx >= 0) ytIdx else 999
                        }
                    })
                    val (pinned, unpinned) = sorted.partition { it in pinnedHomeSections }
                    val ordered = pinned + unpinned

                    // Two keys can point at the same shelf (old saved key vs live title,
                    // different case, fuzzy match). Keep one per shelf, exact title first.
                    // Rendering the same shelf twice crashed the LazyColumn (duplicate key).
                    fun isExactKey(key: String, shelf: HomeShelf) =
                        key.removePrefix("yt:").trim().lowercase() == shelf.title.trim().lowercase()
                    val winnerByShelf = HashMap<String, String>()
                    for (key in ordered) {
                        if (!key.startsWith("yt:")) continue
                        val shelf = ytShelf(key) ?: continue
                        val shelfId = shelf.title.trim().lowercase()
                        val current = winnerByShelf[shelfId]
                        if (current == null || (isExactKey(key, shelf) && !isExactKey(current, shelf))) {
                            winnerByShelf[shelfId] = key
                        }
                    }
                    val winners = winnerByShelf.values.toHashSet()
                    val sections = ordered.filter { key -> !key.startsWith("yt:") || key in winners }
                    sections to winners.associateWith { key -> ytShelf(key)!! }
                }

                val visiblePagedShelfKeys = remember(state.pagedShelves, allHiddenIds, ytHidden) {
                    if (state.isChipActive) emptyList()
                    else {
                        state.pagedShelves.mapIndexedNotNull { shelfIndex, shelf ->
                            val pagedKey = "yt:${shelf.title}"
                            if (shelf.items.isNotEmpty() && !isSectionIdHidden(pagedKey) && !isShelfTitleHidden(shelf.title)) {
                                "paged_shelf_${shelfIndex}_${shelf.title}"
                            } else null
                        }
                    }
                }

                val allContainerKeys = remember(orderedFeedSections, visiblePagedShelfKeys) {
                    orderedFeedSections + visiblePagedShelfKeys
                }

                val isInfiniteRadioVisible = showSynthetic && combinedQuickPicks.isNotEmpty()
                val isQuickTilesVisible = showSynthetic && quickTiles.isNotEmpty()
                val hasSectionsBelowQuickTiles = allContainerKeys.isNotEmpty()
                val quickTilesShape = RoundedCornerShape(
                    topStart = if (isInfiniteRadioVisible) 0.dp else 28.dp,
                    topEnd = if (isInfiniteRadioVisible) 0.dp else 28.dp,
                    bottomStart = if (hasSectionsBelowQuickTiles) 0.dp else 28.dp,
                    bottomEnd = if (hasSectionsBelowQuickTiles) 0.dp else 28.dp,
                )

                val spotlightIndex = allContainerKeys.indexOf("spotlight_hero")
                val hasPermanentContainerAbove = isQuickTilesVisible || isInfiniteRadioVisible
                val getContainerShape: (String) -> RoundedCornerShape = { key ->
                    val index = allContainerKeys.indexOf(key)
                    if (index == -1) {
                        RoundedCornerShape(0.dp)
                    } else {
                        val isFirst = index == 0
                        val isLast = index == allContainerKeys.size - 1
                        val isBeforeSpotlight = spotlightIndex != -1 && index == spotlightIndex - 1
                        val isAfterSpotlight = spotlightIndex != -1 && index == spotlightIndex + 1

                        val topRadius = if ((isFirst && !hasPermanentContainerAbove) || isAfterSpotlight) 28.dp else 0.dp
                        val bottomRadius = if (isLast || isBeforeSpotlight) 28.dp else 0.dp

                        RoundedCornerShape(
                            topStart = topRadius,
                            topEnd = topRadius,
                            bottomStart = bottomRadius,
                            bottomEnd = bottomRadius,
                        )
                    }
                }

                val hasAnyFeedSections = showSynthetic || allContainerKeys.isNotEmpty()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .safeHorizontalContentPadding()
                        // A generic Shape here promoted the whole scrolling viewport to an
                        // offscreen layer: frames re-recorded and re-uploaded its sections
                        // instead of drawing straight to the window. Mask the rounded top
                        // corners at draw time instead, and only when the list actually
                        // scrolls under glass.
                        .drawWithContent {
                            if (!useFeedGlass) {
                                drawContent()
                                return@drawWithContent
                            }
                            drawContent()
                            val left = feedCornerInsetPx
                            val right = size.width - feedCornerInsetPx
                            val top = feedCornerTopPx
                            if (right <= left || size.height <= top) return@drawWithContent
                            val radius = feedCornerRadiusPx
                                .coerceAtMost((right - left) / 2f)
                                .coerceAtMost(size.height - top)
                            if (radius <= 0f) return@drawWithContent
                            val mask = feedBackgroundColor
                            drawRect(mask, Offset.Zero, Size(left, top + radius))
                            drawRect(mask, Offset(right, 0f), Size(size.width - right, top + radius))
                            // No band above `top`: the content there is what the glass
                            // header refracts. Masking it left the glass a flat bar.
                            val topLeft = Path().apply {
                                moveTo(left, top)
                                lineTo(left, top + radius)
                                arcTo(
                                    Rect(left, top, left + 2f * radius, top + 2f * radius),
                                    180f,
                                    90f,
                                    false,
                                )
                                close()
                            }
                            drawPath(topLeft, mask)
                            val topRight = Path().apply {
                                moveTo(right, top)
                                lineTo(right, top + radius)
                                arcTo(
                                    Rect(right - 2f * radius, top, right, top + 2f * radius),
                                    270f,
                                    90f,
                                    false,
                                )
                                close()
                            }
                            drawPath(topRight, mask)
                        },
                    contentPadding = PaddingValues(
                        bottom = FloatingNavDefaults.contentBottomPadding(),
                        top = 16.dp + listTopInset,
                    ),
                    verticalArrangement = Arrangement.Top,
                ) {
                    if (state.isChipActive) {
                        if (state.isChipLoading) {
                            item(key = "chip_loading_skeleton", contentType = "shelf") {
                                HomeContainerSection(shape = RoundedCornerShape(28.dp)) {
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
                            item(key = "chip_empty_state", contentType = "shelf") {
                                HomeContainerSection(shape = RoundedCornerShape(28.dp)) {
                                    PodcastGuestEmptyState(
                                        onLoginClick = onOpenYouTubeLogin,
                                    )
                                }
                            }
                        } else {
                            for ((shelfIndex, shelf) in state.chipShelves.withIndex()) {
                                if (shelf.items.isEmpty()) continue
                                item(key = "chip_shelf_${shelfIndex}_${shelf.title}", contentType = "shelf") {
                                    HomeContainerSection(
                                        shape = RoundedCornerShape(
                                            topStart = if (shelfIndex == 0) 28.dp else 0.dp,
                                            topEnd = if (shelfIndex == 0) 28.dp else 0.dp,
                                            bottomStart = if (shelfIndex == state.chipShelves.size - 1) 28.dp else 0.dp,
                                            bottomEnd = if (shelfIndex == state.chipShelves.size - 1) 28.dp else 0.dp,
                                        ),
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
                        if (showSynthetic) {
                            item(key = "hero_greeting", contentType = "hero_greeting") {
                                ExpressiveGreetingHeader(
                                    greeting = greeting,
                                    formattedDate = formattedDate,
                                )
                            }
                        }

                        if (isInfiniteRadioVisible) {
                            item(key = "infinite_radio", contentType = "infinite_radio") {
                                InfiniteRadioHero(
                                    quickPicks = combinedQuickPicks,
                                    onPlay = viewModel::playInfiniteRadio,
                                )
                            }
                        }

                        if (isQuickTilesVisible) {
                            item(key = "quick_tiles", contentType = "quick_tiles") {
                                HomeContainerSection(
                                    shape = quickTilesShape,
                                ) {
                                    FeedSectionHeader(
                                        title = stringResource(R.string.feed_quick_access),
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    QuickTilesGrid(
                                        tiles = quickTiles,
                                        onTileClick = { tile ->
                                            when {
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

                        for (sectionKey in orderedFeedSections) {
                            when {
                                sectionKey == "taste_strip" -> {
                                    item(key = "taste_strip", contentType = "taste_strip") {
                                        HomeContainerSection(
                                            shape = getContainerShape("taste_strip"),
                                        ) {
                                            TasteStrip(
                                                tags = displayTags,
                                                launching = state.launchingRadio,
                                                onTagClick = { tag -> viewModel.playDiscoveryQuery(tag.displayName(), tag) },
                                                onLongClick = { sectionMenuTarget = "taste_strip" to "My genres" },
                                            )
                                        }
                                    }
                                }
                                sectionKey == "discover_new_music" -> {
                                    item(key = "discover_new_music", contentType = "discover_new_music") {
                                        HomeContainerSection(
                                            shape = getContainerShape("discover_new_music"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_discover),
                                                subtitle = stringResource(R.string.feed_discover_sub),
                                                actionText = stringResource(R.string.feed_see_all),
                                                actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                                onActionClick = onOpenDiscover,
                                                onLongClick = { sectionMenuTarget = "discover_new_music" to "Discover New Music" },
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
                                sectionKey == "quick_picks" -> {
                                    item(key = "quick_picks", contentType = "quick_picks") {
                                        val likelyTracks = combinedQuickPicks.take(2)
                                        LaunchedEffect(likelyTracks) {
                                            viewModel.warmQuickPicks(likelyTracks)
                                        }
                                        HomeContainerSection(
                                            shape = getContainerShape("quick_picks"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_quick_picks),
                                                subtitle = stringResource(R.string.feed_trending_worth),
                                                onShuffleClick = { viewModel.shuffleTracksQueue(combinedQuickPicks, "Quick Picks") },
                                                onLongClick = { sectionMenuTarget = "quick_picks" to "Quick Picks" },
                                            )
                                            QuickPicksRows(
                                                tracks = combinedQuickPicks,
                                                currentPlayingVideoId = playbackState.current?.videoId,
                                                isPlaying = playbackState.isPlaying,
                                                onTrackClick = { index ->
                                                    val track = combinedQuickPicks.getOrNull(index)
                                                    if (track != null) {
                                                        viewModel.playTrack(track, "Quick Picks")
                                                    } else {
                                                        viewModel.playTracksQueue(combinedQuickPicks, index, "Quick Picks")
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
                                sectionKey == "because_you_listen_to" -> {
                                    state.feedData.becauseYouListenTo?.takeIf { it.items.isNotEmpty() }?.let { section ->
                                        item(key = "because_you_listen_to", contentType = "because_you_listen_to") {
                                            HomeContainerSection(
                                                shape = getContainerShape("because_you_listen_to"),
                                            ) {
                                                FeedSectionHeader(
                                                    title = section.title,
                                                    subtitle = section.subtitle,
                                                    onShuffleClick = { viewModel.shuffleTracksQueue(section.items, section.title) },
                                                    onLongClick = { sectionMenuTarget = "because_you_listen_to" to section.title },
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
                                sectionKey == "fresh_finds" -> {
                                    item(key = "fresh_finds", contentType = "fresh_finds") {
                                        HomeContainerSection(
                                            shape = getContainerShape("fresh_finds"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_fresh),
                                                subtitle = stringResource(R.string.feed_fresh_sub),
                                                onShuffleClick = { viewModel.shuffleTracksQueue(state.feedData.freshFinds, "Fresh Finds") },
                                                onLongClick = { sectionMenuTarget = "fresh_finds" to "Fresh Finds" },
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
                                sectionKey == "jump_back_in" -> {
                                    item(key = "jump_back_in", contentType = "jump_back_in") {
                                        HomeContainerSection(
                                            shape = getContainerShape("jump_back_in"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_jump),
                                                subtitle = stringResource(R.string.feed_jump_sub),
                                                onShuffleClick = { viewModel.playRecentQueue(state.feedData.jumpBackIn.shuffled(), 0) },
                                                onLongClick = { sectionMenuTarget = "jump_back_in" to "Jump Back In" },
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
                                sectionKey == "mixed_for_you" || sectionKey == "mixes" -> {
                                    item(key = "mixed_for_you", contentType = "mixed_for_you") {
                                        HomeContainerSection(
                                            shape = getContainerShape("mixed_for_you"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_mixes),
                                                subtitle = stringResource(R.string.feed_mixes_sub),
                                                onShuffleClick = {
                                                    state.feedData.mixes.randomOrNull()?.let(viewModel::playMix)
                                                },
                                                onLongClick = { sectionMenuTarget = "mixed_for_you" to "Mixed For You" },
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
                                sectionKey == "spotlight_hero" || sectionKey == "spotlight" -> {
                                    state.feedData.spotlight?.let { spotlight ->
                                        item(key = "spotlight_hero", contentType = "spotlight_hero") {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 10.dp, bottom = 10.dp),
                                            ) {
                                                SpotlightHeroCard(
                                                    spotlight = spotlight,
                                                    onPlayRadio = {
                                                        viewModel.playArtistRadio(FeedArtist(ArtistHelper.primaryArtist(spotlight.artistName), spotlight.browseId, spotlight.artworkUrl))
                                                    },
                                                    onOpenArtist = {
                                                        artistAlbumNavigator.openArtist(ArtistHelper.primaryArtist(spotlight.artistName), spotlight.browseId ?: "")
                                                    },
                                                    onLongClick = { sectionMenuTarget = "spotlight_hero" to "Artist Spotlight" },
                                                )
                                            }
                                        }
                                    }
                                }
                                sectionKey == "top_artists" -> {
                                    item(key = "top_artists", contentType = "top_artists") {
                                        HomeContainerSection(
                                            shape = getContainerShape("top_artists"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_artists),
                                                subtitle = stringResource(R.string.feed_artists_sub),
                                                onLongClick = { sectionMenuTarget = "top_artists" to "Artists for you" },
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
                                sectionKey == "heavy_rotation" -> {
                                    item(key = "heavy_rotation", contentType = "heavy_rotation") {
                                        HomeContainerSection(
                                            shape = getContainerShape("heavy_rotation"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_favorites),
                                                subtitle = stringResource(R.string.feed_favorites_sub),
                                                onShuffleClick = { viewModel.playGeneratedQueue(state.feedData.heavyRotation.shuffled(), 0, "Heavy Rotation") },
                                                onLongClick = { sectionMenuTarget = "heavy_rotation" to "Heavy Rotation" },
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
                                sectionKey == "albums_in_rotation" || sectionKey == "albums" -> {
                                    item(key = "albums_in_rotation", contentType = "albums_in_rotation") {
                                        HomeContainerSection(
                                            shape = getContainerShape("albums_in_rotation"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_albums),
                                                subtitle = stringResource(R.string.feed_albums_sub),
                                                onLongClick = { sectionMenuTarget = "albums_in_rotation" to "Albums in Rotation" },
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
                                sectionKey == "trending_charts" || sectionKey == "charts" -> {
                                    item(key = "trending_charts", contentType = "trending_charts") {
                                        HomeContainerSection(
                                            shape = getContainerShape("trending_charts"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_trending),
                                                subtitle = stringResource(R.string.feed_trending_sub),
                                                onShuffleClick = { viewModel.shuffleTracksQueue(state.feedData.charts, "Top Charts") },
                                                onLongClick = { sectionMenuTarget = "trending_charts" to "Trending Charts" },
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
                                sectionKey == "new_releases" -> {
                                    item(key = "new_releases", contentType = "new_releases") {
                                        HomeContainerSection(
                                            shape = getContainerShape("new_releases"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_releases),
                                                subtitle = stringResource(R.string.feed_releases_sub),
                                                actionText = stringResource(R.string.feed_see_all),
                                                actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                                onActionClick = onOpenNewReleases,
                                                onLongClick = { sectionMenuTarget = "new_releases" to "New Releases" },
                                            )
                                            FeedMediaRow(
                                                content = {
                                                    items(
                                                        combinedNewReleases,
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
                                sectionKey == "friends_activity" || sectionKey == "friends" -> {
                                    item(key = "friends_activity", contentType = "friends_activity") {
                                        HomeContainerSection(
                                            shape = getContainerShape("friends_activity"),
                                        ) {
                                            FeedSectionHeader(
                                                title = stringResource(R.string.feed_friends),
                                                subtitle = stringResource(R.string.feed_friends_sub),
                                                actionText = stringResource(R.string.feed_see_all),
                                                actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                                onActionClick = onOpenFriends,
                                                onLongClick = { sectionMenuTarget = "friends_activity" to "Friends Activity" },
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
                                sectionKey == "podcast_shelf" || sectionKey == "podcasts" -> {
                                    item(key = "podcast_shelf", contentType = "podcast_shelf") {
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
                                                onLongClick = { sectionMenuTarget = "podcast_shelf" to "Podcasts" },
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
                                sectionKey.startsWith("yt:") -> {
                                    val shelf = ytShelfBySectionKey[sectionKey]
                                    if (shelf != null && shelf.items.isNotEmpty()) {
                                        // unique, orderedFeedSections has one key per shelf
                                        item(key = "yt_shelf_${shelf.title.trim().lowercase()}", contentType = "shelf") {
                                            HomeContainerSection(
                                                shape = getContainerShape(sectionKey),
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
                            }
                            if (sectionKey == orderedFeedSections.firstOrNull { it == "podcast_shelf" || it == "podcasts" }) {
                                item(key = "moods_and_genres", contentType = "moods_and_genres") {
                                    HomeContainerSection {
                                        com.lastwave.app.ui.moods.HomeMoodsSection()
                                    }
                                }
                            }
                        }

                        if (orderedFeedSections.none { it == "podcast_shelf" || it == "podcasts" }) {
                            item(key = "moods_and_genres", contentType = "moods_and_genres") {
                                HomeContainerSection {
                                    com.lastwave.app.ui.moods.HomeMoodsSection()
                                }
                            }
                        }

                        if (!hasAnyFeedSections && !state.isChipActive) {
                            item(key = "all_sections_hidden", contentType = "all_sections_hidden") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 60.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            modifier = Modifier.size(64.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Filled.VisibilityOff,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(32.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = "All home sections are hidden",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "Customize and re-enable sections in Settings → Library & Content → Home sections.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!state.isChipActive && hasAnyFeedSections) {
                        for ((shelfIndex, shelf) in state.pagedShelves.withIndex()) {
                            val shelfItemKey = "paged_shelf_${shelfIndex}_${shelf.title}"
                            val pagedKey = "yt:${shelf.title}"
                            if (shelf.items.isEmpty() || isSectionIdHidden(pagedKey) || isShelfTitleHidden(shelf.title)) continue
                            item(key = shelfItemKey, contentType = "shelf") {
                                HomeContainerSection(
                                    shape = getContainerShape(shelfItemKey),
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

                    if (state.isLoadingMore && hasAnyFeedSections) {
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

                    if (showSynthetic && hasAnyFeedSections) {
                        item(key = "feed_footer", contentType = "feed_footer") {
                            FeedFooter(lastUpdatedMillis = state.feedData.lastUpdatedMillis)
                        }
                    }
                }
            }
        }

            // Glass header on top of the feed, with the pinned YTM chip row
            // under it in its original opaque style (not part of the glass).
            val chipStripColor = MaterialTheme.colorScheme.background
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { if (it.height != topBarHeightPx) topBarHeightPx = it.height },
            ) {
            ExpressiveHeader(
                title = stringResource(R.string.feed_home),
                containerColor = if (isPodcastsActive) Color.Transparent else Color.Unspecified,
                backdrop = if (useFeedGlass) feedBackdrop else null,
                actions = {
                    HeaderActionIcon(Icons.Filled.Download, "Downloads", onOpenDownloads)
                    HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                    IconButton(onClick = onOpenSettings) {
                        com.lastwave.app.ui.home.ProfileAvatar(avatarUrl = state.avatarUrl, modifier = Modifier.size(38.dp))
                    }
                },
            )
            if (state.homeChips.isNotEmpty()) {
                // Page-coloured strip so the feed scrolling under the glass
                // never shows through around the chips (looks as before).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (useFeedGlass) Modifier.background(chipStripColor) else Modifier),
                ) {
                    PinnedChipsBar(
                        chips = state.homeChips,
                        selectedChip = state.selectedChip,
                        onChipClick = viewModel::onHomeChipSelected,
                    )
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
