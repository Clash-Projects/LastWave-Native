package com.lastwave.app.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.music.*
import com.lastwave.app.ui.feed.cards.*
import kotlinx.coroutines.flow.collect

/** Only endpoint-qualified community and daily shelves enter this presentation path. */
@Composable
internal fun CommunityDiscoverShelf(
    shelf: HomeShelf,
    onPlayTrack: (YouTubeMusicTrack, String) -> Unit,
    onPlayTracksQueue: (List<YouTubeMusicTrack>, Int, String) -> Unit,
    onPlayPlaylist: (YouTubePlaylistSummary) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onTrackMenu: (YouTubeMusicTrack) -> Unit,
    onTrackLongClick: (YouTubeMusicTrack) -> Unit,
    modifier: Modifier = Modifier,
    fallback: @Composable (HomeShelfItem) -> Unit,
) {
    val daily = isDailyDiscoverShelf(shelf)
    val tracks = remember(shelf.items) { shelf.items.filterIsInstance<HomeShelfItem.Track>().map { it.track } }
    val moreId = shelf.moreBrowseId?.removePrefix("VL")
        ?.takeIf { it.startsWith("PL") || it.startsWith("MPRE") || it.startsWith("RD") || it.startsWith("OLAK") }
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        ShelfHeaderV2(
            title = shelf.title, eyebrow = shelf.strapline, subtitle = shelf.subtitle,
            showMore = moreId != null, onMoreClick = moreId?.let { { onOpenPlaylist(it) } },
            onShuffle = if (tracks.isNotEmpty()) { { onPlayTracksQueue(tracks.shuffled(), 0, shelf.title) } } else null,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = largeShelfCardWidth(maxWidth)
            val rowState = rememberLazyListState()
            val view = LocalView.current
            var rowVisible by remember { mutableStateOf(false) }
            val keys = remember(shelf.items) { stableShelfCardKeys(shelf.items) }
            LazyRow(
                state = rowState,
                flingBehavior = rememberSnapFlingBehavior(rowState, snapPosition = SnapPosition.Start),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
                modifier = Modifier.padding(top = 10.dp).onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    rowVisible = bounds.height > 0f && bounds.width > 0f &&
                        bounds.bottom > 0f && bounds.top < view.height
                },
            ) {
                itemsIndexed(shelf.items, key = { index, _ -> keys[index] }) { index, item ->
                    val visible by remember(index) { derivedStateOf {
                        rowVisible && rowState.layoutInfo.visibleItemsInfo.any { it.index == index }
                    } }
                    when {
                        isCommunityPlaylistItem(item) -> {
                            val playlist = item as HomeShelfItem.Playlist
                            CommunityPlaylistShelfItem(playlist, visible, width,
                                onOpen = { onOpenPlaylist(playlist.playlist.id) },
                                onPlay = { onPlayPlaylist(playlist.playlist) },
                                onSong = { onPlayTrack(it, playlist.playlist.title) },
                                onMenu = onTrackMenu)
                        }
                        daily && item is HomeShelfItem.Track -> ShelfFeaturedCard(
                            title = item.track.title,
                            artist = listOfNotNull(item.track.artist.takeIf(String::isNotBlank), item.cardData?.playsText).joinToString(" • "),
                            artworkUrl = item.cardData?.largeArtworkUrl ?: item.track.artworkUrl,
                            caption = item.caption,
                            onClick = { onPlayTrack(item.track, shelf.title) },
                            onLongClick = { onTrackLongClick(item.track) },
                            onMenuClick = { onTrackMenu(item.track) },
                            cardWidth = width, isVisible = visible,
                        )
                        else -> fallback(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityPlaylistShelfItem(
    item: HomeShelfItem.Playlist,
    visible: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onSong: (YouTubeMusicTrack) -> Unit,
    onMenu: (YouTubeMusicTrack) -> Unit,
    model: PlaylistPreviewViewModel = hiltViewModel(),
) {
    val loader by model.loader.collectAsStateWithLifecycle()
    var state by remember(loader, item.playlist.id) { mutableStateOf<PlaylistPreviewState>(PlaylistPreviewState.Loading) }
    LaunchedEffect(loader, item.playlist.id, visible) {
        if (visible) loader.load(item.playlist.id).collect { state = it }
    }
    val preview = (state as? PlaylistPreviewState.Ready)?.preview
    val subtitle = listOfNotNull(
        item.playlist.author?.takeIf(String::isNotBlank), item.cardData?.viewsText,
        preview?.songCount?.let { if (it == 1) "1 song" else "$it songs" },
    ).joinToString(" • ")
    ShelfCommunityPlaylistCard(
        playlistId = item.playlist.id,
        title = item.playlist.title, subtitle = subtitle,
        collageUrls = preview?.collageArtworkUrls.orEmpty(), songs = preview?.songs.orEmpty(),
        artworkUrl = item.playlist.artworkUrl, onClick = onOpen, onPlayClick = onPlay,
        onSongClick = onSong, onSongMenuClick = onMenu, cardWidth = width,
        isLoading = state is PlaylistPreviewState.Loading && visible,
        previewFailed = state is PlaylistPreviewState.Failed,
        songPlayCounts = preview?.songPlayCounts.orEmpty(),
    )
}

internal fun stableShelfCardKeys(items: List<HomeShelfItem>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return items.map {
        val id = when (it) {
            is HomeShelfItem.Track -> "track:${it.track.videoId}"
            is HomeShelfItem.Playlist -> "playlist:${it.playlist.id}"
            is HomeShelfItem.PodcastEpisode -> "episode:${it.videoId}"
            is HomeShelfItem.PodcastShow -> "show:${it.browseId}"
        }
        val occurrence = occurrences.getOrDefault(id, 0)
        occurrences[id] = occurrence + 1
        "$id:$occurrence"
    }
}

internal fun largeShelfCardWidth(available: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (available * 0.88f).coerceIn(300.dp, 420.dp)
        .coerceAtMost((available - 40.dp).coerceAtLeast(160.dp))
