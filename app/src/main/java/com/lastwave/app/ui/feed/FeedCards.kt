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



@Composable
internal fun SquareItemCard(
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
internal fun WideItemCard(
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
internal fun FeaturedItemCard(
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
internal fun CommunityItemCard(
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
internal fun FeedMediaRow(
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
internal fun FeedMediaCard(
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
    val coverHeight = cardWidth
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
internal fun FeedPlaylistCard(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecentTrackCard(
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
    val coverHeight = cardWidth
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
internal fun ChartTrackCard(
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
