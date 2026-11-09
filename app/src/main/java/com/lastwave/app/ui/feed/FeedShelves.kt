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
import androidx.compose.ui.draw.clip
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
import com.lastwave.app.ui.common.TopCurvedSectionClipShape
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



/**
 * Reusable surface card for a YouTube Music carousel shelf.
 * Renders each item through the card system (square / wide / featured /
 * community) chosen from the item's real YTM data, falling back to square.
 * Used for page-1 native shelves, continuation paged shelves, and chip shelves.
 */
@Composable
internal fun HomeShelfCard(
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
            onHeaderLongClick = onHeaderLongClick?.let { { it(shelf.title) } },
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
        val displayTitle = remember(shelf.title) {
            val t = shelf.title.trim()
            if (t.contains("Fresh finds", ignoreCase = true) && t.contains("old favorite", ignoreCase = true)) {
                "Fresh finds, old & favorites"
            } else {
                t
            }
        }
        com.lastwave.app.ui.feed.cards.ShelfHeaderV2(
            title = displayTitle,
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

@Composable
internal fun NativeShelfItemCard(
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
internal fun PodcastEpisodeItemCard(
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

@Composable
internal fun PodcastGuestEmptyState(
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SpotlightHeroCard(
    spotlight: FeedSpotlight,
    onPlayRadio: () -> Unit,
    onOpenArtist: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
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
            .padding(horizontal = 16.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenArtist,
                onLongClick = onLongClick?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            ),
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
internal fun ArtistAvatarCard(
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
internal fun FriendAvatarCard(
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
internal fun HomeContainerSection(
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
internal fun FeedSectionHeader(
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
