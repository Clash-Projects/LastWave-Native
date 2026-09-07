package com.lastwave.app.ui.feed

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.data.feed.FeedAlbum
import com.lastwave.app.data.feed.FeedArtist
import com.lastwave.app.data.feed.FeedQuickTile
import com.lastwave.app.data.feed.FeedSpotlight
import com.lastwave.app.util.ArtistHelper
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.WindowSizeClass
import com.lastwave.app.ui.common.rememberWindowSizeClass
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.liquidGlassContainerColor

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
    onOpenFriendProfile: (username: String, displayName: String?, avatarUrl: String?) -> Unit = { _, _, _ -> },
    onOpenNewReleases: () -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
    artistAlbumNavigator: ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeFeed>().navigator,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onVisible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()
    var menuTrack by remember { mutableStateOf<YouTubeMusicTrack?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val hasFeedContent = with(state.feedData) {
        quickTiles.isNotEmpty() || mixes.isNotEmpty() || topArtists.isNotEmpty() ||
            quickPicks.isNotEmpty() || jumpBackIn.isNotEmpty() || recentAlbums.isNotEmpty() ||
            heavyRotation.isNotEmpty() || ytLikedSongs.isNotEmpty() || ytRecentSongs.isNotEmpty() ||
            (becauseYouListenTo?.items?.isNotEmpty() == true) || freshFinds.isNotEmpty() ||
            spotlight != null || charts.isNotEmpty() || newReleases.isNotEmpty() || friends.isNotEmpty()
    }
    val greeting = remember {
        when (java.time.LocalTime.now().hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
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
            val result = snackbarHostState.showSnackbar(error, actionLabel = "Retry", withDismissAction = true)
            viewModel.dismissError()
            if (result == SnackbarResult.ActionPerformed) viewModel.refresh()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 920.dp),
        ) {
            ExpressiveHeader(
                title = "Home",
                actions = {
                    HeaderActionIcon(Icons.Filled.Explore, "Discover Radar", onOpenDiscover)
                    HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                    HeaderActionIcon(Icons.Filled.Settings, "Settings", onOpenSettings)
                },
            )

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

                LazyColumn(
                    modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    contentPadding = PaddingValues(
                        bottom = FloatingNavDefaults.contentBottomPadding(),
                        top = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item(key = "hero") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

                    val quickTiles = if (state.feedData.isYtConnected) {
                        state.feedData.quickTiles
                    } else {
                        state.feedData.quickTiles.filter {
                            it.collection != "yt_liked" && it.collection != "yt_recent" &&
                                it.playlistId != "yt_liked" && it.playlistId != "yt_recent"
                        }
                    }
                    if (quickTiles.isNotEmpty()) {
                        item(key = "quick_tiles") {
                            QuickTilesGrid(
                                tiles = quickTiles,
                                onTileClick = { tile ->
                                    when {
                                        tile.collection == "radio" -> viewModel.playInfiniteRadio()
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

                    if (state.feedData.tasteTags.isNotEmpty()) {
                        item(key = "taste_strip") {
                            TasteStrip(
                                tags = state.feedData.tasteTags,
                                launching = state.launchingRadio,
                                onTagClick = { tag -> viewModel.playDiscoveryQuery(tag.displayName(), tag) },
                            )
                        }
                    }

                    if (state.feedData.quickPicks.isNotEmpty()) {
                        item(key = "quick_picks") {
                            val title = if (state.feedData.hasYtRecommendations) "Picked for you" else "Quick picks"
                            val subtitle = if (state.feedData.hasYtRecommendations) {
                                "From your listening - refreshed for you"
                            } else if (state.feedData.hasPersonalContent) {
                                "Matched to your taste profile"
                            } else {
                                "Trending songs worth playing now"
                            }
                            FeedSectionHeader(
                                title = title,
                                subtitle = subtitle,
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(state.feedData.quickPicks, 0, "Quick Picks") },
                                onShuffleClick = { viewModel.shuffleTracksQueue(state.feedData.quickPicks, "Quick Picks") },
                            )
                            QuickPicksRows(
                                tracks = state.feedData.quickPicks,
                                currentPlayingVideoId = playbackState.current?.videoId,
                                isPlaying = playbackState.isPlaying,
                                onTrackClick = { index -> viewModel.playTracksQueue(state.feedData.quickPicks, index, "Quick Picks") },
                                onMenuClick = { menuTrack = it },
                            )
                        }
                    }

                    state.feedData.becauseYouListenTo?.takeIf { it.items.isNotEmpty() }?.let { section ->
                        item(key = "because_you_listen_to") {
                            FeedSectionHeader(
                                title = section.title,
                                subtitle = section.subtitle,
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(section.items, 0, section.title) },
                                onShuffleClick = { viewModel.shuffleTracksQueue(section.items, section.title) },
                            )
                            FeedMediaRow(
                                content = {
                                    itemsIndexed(section.items) { index, track ->
                                        FeedMediaCard(
                                            title = track.title,
                                            subtitle = ArtistHelper.primaryArtist(track.artist),
                                            artworkUrl = track.artworkUrl,
                                            fallbackIcon = Icons.Filled.MusicNote,
                                            onClick = { viewModel.playTracksQueue(section.items, index, section.title) },
                                            onPlayClick = { viewModel.playTracksQueue(section.items, index, section.title) },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    if (state.feedData.freshFinds.isNotEmpty()) {
                        item(key = "fresh_finds") {
                            FeedSectionHeader(
                                title = "Fresh finds",
                                subtitle = "New tracks beyond your usual rotation",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(state.feedData.freshFinds, 0, "Fresh Finds") },
                                onShuffleClick = { viewModel.shuffleTracksQueue(state.feedData.freshFinds, "Fresh Finds") },
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
                                            onClick = { viewModel.playTracksQueue(state.feedData.freshFinds, index, "Fresh Finds") },
                                            onPlayClick = { viewModel.playTracksQueue(state.feedData.freshFinds, index, "Fresh Finds") },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    if (state.feedData.jumpBackIn.isNotEmpty()) {
                        item(key = "jump_back_in") {
                            FeedSectionHeader(
                                title = "Jump back in",
                                subtitle = "From your listening history",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playRecentQueue(state.feedData.jumpBackIn, 0) },
                            )
                            FeedMediaRow(
                                content = {
                                    itemsIndexed(state.feedData.jumpBackIn) { index, track ->
                                        RecentTrackCard(
                                            track = track,
                                            onClick = { viewModel.playRecentQueue(state.feedData.jumpBackIn, index) },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    if (state.feedData.mixes.isNotEmpty()) {
                        item(key = "mixed_for_you") {
                            FeedSectionHeader(
                                title = "Mixes to explore",
                                subtitle = "Familiar favorites, fresh combinations",
                                actionText = "Shuffle",
                                actionIcon = Icons.Filled.Shuffle,
                                onActionClick = {
                                    state.feedData.mixes.randomOrNull()?.let(viewModel::playMix)
                                },
                            )
                            FeedMediaRow(
                                content = {
                                    items(state.feedData.mixes, key = { it.seed.videoId }) { mix ->
                                        FeedPlaylistCard(
                                            title = mix.title,
                                            subtitle = "Endless artist radio",
                                            artworkUrl = mix.seed.artworkUrl,
                                            onClick = { viewModel.playMix(mix) },
                                            onPlayClick = { viewModel.playMix(mix) },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    state.feedData.spotlight?.let { spotlight ->
                        item(key = "spotlight_hero") {
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

                    if (individualTopArtists.isNotEmpty()) {
                        item(key = "top_artists") {
                            FeedSectionHeader(
                                    title = "Artists for you",
                                    subtitle = "Worth another listen",
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

                    if (state.feedData.heavyRotation.isNotEmpty()) {
                        item(key = "heavy_rotation") {
                            FeedSectionHeader(
                                title = "Favorites to revisit",
                                subtitle = "From your listening profile",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playGeneratedQueue(state.feedData.heavyRotation, 0, "Heavy Rotation") },
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
                                            onPlayClick = { viewModel.playGeneratedQueue(state.feedData.heavyRotation, index, "Heavy Rotation") },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    if (state.feedData.recentAlbums.isNotEmpty()) {
                        item(key = "albums_in_rotation") {
                            FeedSectionHeader(
                                title = "Albums for you",
                                subtitle = "From your listening and recommendations",
                            )
                            FeedMediaRow(
                                content = {
                                    items(state.feedData.recentAlbums) { album ->
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

                    if (state.feedData.charts.isNotEmpty()) {
                        item(key = "trending_charts") {
                            FeedSectionHeader(
                                title = "Trending now",
                                subtitle = "Most popular right now · tap a rank to play",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(state.feedData.charts, 0, "Top Charts") },
                                onShuffleClick = { viewModel.shuffleTracksQueue(state.feedData.charts, "Top Charts") },
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
                                        onClick = { viewModel.playTracksQueue(state.feedData.charts, index, "Top Charts") },
                                    )
                                }
                            }
                        }
                    }

                    if (state.feedData.newReleases.isNotEmpty()) {
                        item(key = "new_releases") {
                            FeedSectionHeader(
                                title = "New releases",
                                subtitle = "Fresh drops and new albums",
                                actionText = "See all",
                                actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                onActionClick = onOpenNewReleases,
                            )
                            FeedMediaRow(
                                content = {
                                    items(state.feedData.newReleases) { summary ->
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

                    if (state.feedData.friends.isNotEmpty()) {
                        item(key = "friends_activity") {
                            FeedSectionHeader(
                                title = "Your friends",
                                subtitle = "People in your listening circle",
                                actionText = "See all",
                                actionIcon = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                onActionClick = onOpenFriends,
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(state.feedData.friends) { friend ->
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

                    item(key = "feed_footer") {
                        FeedFooter(lastUpdatedMillis = state.feedData.lastUpdatedMillis)
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
}

private fun String.displayName(): String =
    split(" ", "-", "_").filter { it.isNotBlank() }.joinToString(" ") {
        it.replaceFirstChar { c -> c.uppercase() }
    }

private fun relativeTime(uts: String?): String? {
    val epoch = uts?.toLongOrNull() ?: return null
    val now = System.currentTimeMillis() / 1000
    val diff = (now - epoch).coerceAtLeast(0)
    return when {
        diff < 3600 -> "${(diff / 60).coerceAtLeast(1)}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 86400 * 7 -> "${diff / 86400}d ago"
        diff < 86400 * 30 -> "${diff / (86400 * 7)}w ago"
        else -> "${diff / (86400 * 30)}mo ago"
    }
}

@Composable
private fun InfiniteRadioHero(
    quickPicks: List<YouTubeMusicTrack>,
    onPlay: () -> Unit,
) {
    val liquidGlass = LocalLiquidGlass.current
    val haptics = LocalHapticFeedback.current
    val heroArt = quickPicks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
    val heroShape = RoundedCornerShape(28.dp)
    Surface(
        shape = heroShape,
        color = liquidGlassContainerColor(MaterialTheme.colorScheme.surfaceContainer),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .liquidGlassChrome(heroShape, liquidGlass),
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
                        Text("Play", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
            title = "Your sound",
            subtitle = "Tap a vibe to start instant radio",
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            items(tags, key = { it }) { tag ->
                val isLoading = launching?.equals(tag.displayName(), ignoreCase = true) == true
                LiquidGlassSurface(
                    glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current),
                    onClick = { onTagClick(tag) },
                    shape = CircleShape,
                    color = liquidGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)),
                    modifier = Modifier,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
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
                                contentDescription = "Starting radio",
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
    val label = remember(lastUpdatedMillis) {
        if (lastUpdatedMillis <= 0L) "Made for you from your taste"
        else try {
            val time = java.time.Instant.ofEpochMilli(lastUpdatedMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
            "Updated $time · Made for you from your taste"
        } catch (_: Exception) {
            "Made for you from your taste"
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
    val sizeClass = rememberWindowSizeClass()
    val columns = when (sizeClass) {
        WindowSizeClass.COMPACT -> 2
        WindowSizeClass.MEDIUM -> 3
        WindowSizeClass.EXPANDED -> 4
    }
    val tileLimit = when (sizeClass) {
        WindowSizeClass.COMPACT -> 6
        WindowSizeClass.MEDIUM -> 6
        WindowSizeClass.EXPANDED -> 8
    }
    val rows = remember(tiles, columns, tileLimit) { tiles.take(tileLimit).chunked(columns) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { tile ->
                    QuickTileCard(
                        tile = tile,
                        modifier = Modifier.weight(1f),
                        onClick = { onTileClick(tile) },
                    )
                }
                val remaining = columns - rowItems.size
                if (remaining > 0) {
                    repeat(remaining) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTileCard(tile: FeedQuickTile, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val liquidGlass = LocalLiquidGlass.current
    val tileShape = RoundedCornerShape(18.dp)
    LiquidGlassSurface(
        glassModifier = Modifier.liquidGlassChrome(tileShape, liquidGlass),
        onClick = onClick,
        shape = tileShape,
        color = liquidGlassContainerColor(
            if (tile.isLiked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
        modifier = modifier
            .height(64.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                    .background(
                        if (tile.isLiked) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (tile.isLiked) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                } else if (!tile.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = tile.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (tile.collection == "new_releases") {
                    Icon(
                        Icons.Filled.NewReleases,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = tile.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 17.sp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = tile.subtitle ?: if (tile.actionVideoId != null) "Track" else "Playlist",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            if (tile.actionVideoId != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPicksRows(
    tracks: List<YouTubeMusicTrack>,
    currentPlayingVideoId: String?,
    isPlaying: Boolean,
    onTrackClick: (Int) -> Unit,
    onMenuClick: (YouTubeMusicTrack) -> Unit,
) {
    val sizeClass = rememberWindowSizeClass()
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
                        onClick = { onTrackClick(overallIndex) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f),
                        border = if (isCurrent) androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        ) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(modifier = Modifier.size(56.dp)) {
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
                                    maxLines = 1,
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
                                    contentDescription = "More options for ${track.title}",
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 12.dp),
        content = content,
    )
}

@Composable
private fun FeedMediaCard(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    fallbackIcon: ImageVector,
    onClick: () -> Unit,
    onPlayClick: (() -> Unit)? = null,
    badgeText: String? = null,
    cardWidth: androidx.compose.ui.unit.Dp = 148.dp,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
    ) {
        Box(modifier = Modifier.size(cardWidth)) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxSize(),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
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
            if (onPlayClick != null) {
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayClick()
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 8.dp)
                        .size(38.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play $title",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
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
        cardWidth = 156.dp,
    )
}

// Legacy card variants were consolidated into FeedMediaCard / FeedPlaylistCard
// so every shelf shares one artwork, type and play-affordance language.

@Composable
private fun RecentTrackCard(
    track: RecentTrack,
    onClick: () -> Unit,
) {
    val ago = remember(track.date?.uts, track.url) { relativeTime(track.date?.uts) }
    val primaryArtistName = remember(track.artist.displayName) { ArtistHelper.primaryArtist(track.artist.displayName) }
    val subtitle = if (ago != null) "$primaryArtistName · $ago" else primaryArtistName
    Column(
        modifier = Modifier
            .width(148.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
            modifier = Modifier.size(148.dp),
            onClick = onClick,
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ChartTrackCard(
    rank: Int,
    track: YouTubeMusicTrack,
    onClick: () -> Unit,
) {
    val isTop3 = rank <= 3
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        shape = RoundedCornerShape(18.dp),
        color = if (isTop3) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isTop3) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
        modifier = Modifier
            .width(288.dp)
            .height(76.dp),
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
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
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
    val liquidGlass = LocalLiquidGlass.current
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
        color = liquidGlassContainerColor(MaterialTheme.colorScheme.surfaceContainer),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .liquidGlassChrome(RoundedCornerShape(28.dp), liquidGlass),
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
                                contentDescription = "Open $primaryArtistName",
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
                        Text("Artist radio", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    LiquidGlassSurface(
                        glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current),
                        onClick = onOpenArtist,
                        shape = CircleShape,
                        color = liquidGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)),
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
private fun FeedSectionHeader(
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
    onShuffleClick: (() -> Unit)? = null,
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
                .then(
                    if (onActionClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onActionClick,
                        )
                    } else Modifier
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        if (onShuffleClick != null) {
            LiquidGlassSurface(
                glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onShuffleClick()
                },
                shape = CircleShape,
                color = liquidGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)),
            ) {
                Box(
                    modifier = Modifier.padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Shuffle $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (actionText != null && onActionClick != null) {
            val liquidGlass = LocalLiquidGlass.current
            LiquidGlassSurface(
                glassModifier = Modifier.liquidGlassChrome(CircleShape, liquidGlass, LiquidGlassPreset.FloatingControls),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onActionClick()
                },
                shape = CircleShape,
                color = liquidGlassContainerColor(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    actionIcon?.let { icon ->
                        Icon(
                            icon,
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
                Text("Search music", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            LiquidGlassSurface(
                glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current),
                onClick = onRetry,
                shape = CircleShape,
                color = liquidGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)),
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
