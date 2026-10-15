package com.lastwave.app.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.ui.feed.FeedViewModel
import com.lastwave.app.ui.home.HomeViewModel
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.tv.components.TvMusicCard
import com.lastwave.app.ui.tv.components.TvSectionRow
import androidx.compose.ui.focus.focusRestorer
import com.lastwave.app.ui.tv.focus.PositionFocusedItemInLazyLayout
import com.lastwave.app.ui.tv.focus.tvFocusHighlight

@Composable
fun TvHomeScreen(
    modifier: Modifier = Modifier,
    feedViewModel: FeedViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
) {
    val feedUiState by feedViewModel.uiState.collectAsStateWithLifecycle()
    val discoveryTracks by feedViewModel.discoveryTracks.collectAsStateWithLifecycle()
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    val musicPlayer = LocalMusicPlayer.current
    val currentTrack = musicPlayer.state.collectAsStateWithLifecycle().value.current
    val openPlayer = LocalTvOpenPlayer.current

    LaunchedEffect(Unit) {
        feedViewModel.onVisible()
    }

    val feedData = feedUiState.feedData
    val charts = feedData.charts
    val quickPicks = feedData.quickPicks
    val freshFinds = feedData.freshFinds
    val newReleases = feedData.newReleases
    val recentTracks = homeUiState.allTracks
    val topArtists = if (feedData.topArtists.isNotEmpty()) feedData.topArtists else emptyList()
    val homeTopArtists = homeUiState.topArtists
    val topAlbums = homeUiState.topAlbums

    // Pre-compute queues at Composable scope (capped to 16 items for 1GB RAM TV performance)
    val recommendationTracks = remember(discoveryTracks, quickPicks) {
        val list = if (discoveryTracks.isNotEmpty()) {
            discoveryTracks.map { it.toPlayableTrack() }
        } else {
            quickPicks.map {
                PlayableTrack(
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    artworkUrl = it.artworkUrl,
                    videoId = it.videoId,
                )
            }
        }
        list.take(16)
    }

    val chartQueue = remember(charts) {
        charts.take(16).map {
            PlayableTrack(
                title = it.title,
                artist = it.artist,
                album = it.album,
                artworkUrl = it.artworkUrl,
                videoId = it.videoId,
            )
        }
    }

    val freshQueue = remember(freshFinds) {
        freshFinds.take(16).map {
            PlayableTrack(
                title = it.title,
                artist = it.artist,
                album = it.album,
                artworkUrl = it.artworkUrl,
                videoId = it.videoId,
            )
        }
    }

    val recentPlaybackQueue = remember(recentTracks) {
        recentTracks.take(16).map { track ->
            PlayableTrack(
                title = track.name,
                artist = track.artist,
                artworkUrl = track.artworkUrl,
                videoId = track.videoId,
            )
        }
    }

    val hasAnyContent = recommendationTracks.isNotEmpty() ||
        chartQueue.isNotEmpty() ||
        freshQueue.isNotEmpty() ||
        newReleases.isNotEmpty() ||
        recentPlaybackQueue.isNotEmpty()

    val isInitialLoading = (feedUiState.isLoading || discoveryTracks.isEmpty()) && !hasAnyContent

    if (isInitialLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = "Loading recommendations...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (!hasAnyContent) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text(
                    text = "No recommendations loaded yet",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Make sure your TV is connected to the internet, or try refreshing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvActionChip(
                    text = "Refresh Recommendations",
                    icon = Icons.Default.Refresh,
                    onClick = { feedViewModel.refresh() },
                )
            }
        }
        return
    }

    PositionFocusedItemInLazyLayout(
        parentFraction = 0.3f,
        childFraction = 0.3f,
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .focusRestorer(),
            contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
        ) {
        // Welcome Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val displayName = if (homeUiState.username.isNotBlank() && homeUiState.username != "Guest User") {
                        homeUiState.username
                    } else if (feedData.ytAccountName != null) {
                        feedData.ytAccountName
                    } else {
                        "to LastWave"
                    }
                    Text(
                        text = "Welcome $displayName",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Personalized music & video streaming for TV",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TvActionChip(
                    text = "Refresh",
                    icon = Icons.Default.Refresh,
                    onClick = { feedViewModel.refresh() },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 1: Personalized Recommendations
        if (recommendationTracks.isNotEmpty()) {
            item {
                TvSectionRow(
                    title = "Recommended For You",
                    subtitle = "Dual-engine smart picks & discoveries",
                ) {
                    itemsIndexed(recommendationTracks, key = { index, track -> "rec_${track.videoId ?: track.title}_$index" }) { index, track ->
                        val isPlaying = currentTrack?.title == track.title && currentTrack.artist == track.artist
                        TvMusicCard(
                            title = track.title,
                            subtitle = track.artist,
                            imageUrl = track.artworkUrl,
                            isPlaying = isPlaying,
                            onClick = {
                                musicPlayer.playQueue(
                                    tracks = recommendationTracks,
                                    startIndex = index,
                                    sourceLabel = "TV Recommendations",
                                )
                                openPlayer()
                            },
                        )
                    }
                }
            }
        }

        // Section 2: Trending & Charts
        if (chartQueue.isNotEmpty()) {
            item {
                TvSectionRow(
                    title = "Trending Charts",
                    subtitle = "Top global tracks streaming right now",
                ) {
                    itemsIndexed(chartQueue, key = { index, track -> "chart_${track.videoId ?: track.title}_$index" }) { index, track ->
                        val isPlaying = currentTrack?.title == track.title && currentTrack.artist == track.artist
                        TvMusicCard(
                            title = track.title,
                            subtitle = track.artist,
                            imageUrl = track.artworkUrl,
                            isPlaying = isPlaying,
                            badge = "#${index + 1}",
                            onClick = {
                                musicPlayer.playQueue(
                                    tracks = chartQueue,
                                    startIndex = index,
                                    sourceLabel = "TV Charts",
                                )
                                openPlayer()
                            },
                        )
                    }
                }
            }
        }

        // Section 3: Fresh Finds
        if (freshQueue.isNotEmpty()) {
            item {
                TvSectionRow(
                    title = "Fresh Finds",
                    subtitle = "Breakthrough tracks and new sounds",
                ) {
                    itemsIndexed(freshQueue, key = { index, track -> "fresh_${track.videoId ?: track.title}_$index" }) { index, track ->
                        val isPlaying = currentTrack?.title == track.title && currentTrack.artist == track.artist
                        TvMusicCard(
                            title = track.title,
                            subtitle = track.artist,
                            imageUrl = track.artworkUrl,
                            isPlaying = isPlaying,
                            onClick = {
                                musicPlayer.playQueue(
                                    tracks = freshQueue,
                                    startIndex = index,
                                    sourceLabel = "Fresh Finds",
                                )
                                openPlayer()
                            },
                        )
                    }
                }
            }
        }

        // Section 4: New Releases
        if (newReleases.isNotEmpty()) {
            item {
                TvSectionRow(
                    title = "New Releases",
                    subtitle = "Fresh singles and albums",
                ) {
                    itemsIndexed(newReleases, key = { index, item -> "release_${item.id}_$index" }) { _, release ->
                        TvMusicCard(
                            title = release.title,
                            subtitle = release.author ?: "New Release",
                            imageUrl = release.artworkUrl,
                            onClick = {
                                release.author?.let { artist ->
                                    onOpenAlbum(release.title, artist)
                                }
                            },
                        )
                    }
                }
            }
        }

        // Section 5: Recently Played / Library Tracks
        if (recentPlaybackQueue.isNotEmpty()) {
            item {
                TvSectionRow(
                    title = "Recently Played",
                    subtitle = "Pick up where you left off",
                ) {
                    itemsIndexed(recentPlaybackQueue, key = { index, track -> "recent_${track.videoId ?: track.title}_$index" }) { index, track ->
                        val isPlaying = currentTrack?.title == track.title && currentTrack.artist == track.artist
                        TvMusicCard(
                            title = track.title,
                            subtitle = track.artist,
                            imageUrl = track.artworkUrl,
                            isPlaying = isPlaying,
                            onClick = {
                                musicPlayer.playQueue(
                                    tracks = recentPlaybackQueue,
                                    startIndex = index,
                                    sourceLabel = "TV Recent Listens",
                                )
                                openPlayer()
                            },
                        )
                    }
                }
            }
        }

        // Section 6: Top Artists
        if (topArtists.isNotEmpty() || homeTopArtists.isNotEmpty()) {
            item {
                TvSectionRow(
                    title = "Top Artists",
                    subtitle = "Popular artists in your rotation",
                ) {
                    if (topArtists.isNotEmpty()) {
                        itemsIndexed(topArtists.take(12), key = { _, artist -> "artist_feed_${artist.name}" }) { _, artist ->
                            TvMusicCard(
                                title = artist.name,
                                subtitle = "Artist",
                                imageUrl = artist.artworkUrl,
                                isCircle = true,
                                cardWidth = 160.dp,
                                onClick = { onOpenArtist(artist.name) },
                            )
                        }
                    } else {
                        itemsIndexed(homeTopArtists.take(12), key = { _, artist -> "artist_home_${artist.name}" }) { _, artist ->
                            TvMusicCard(
                                title = artist.name,
                                subtitle = if (artist.playCount > 0L) "${artist.playCount} scrobbles" else "Artist",
                                imageUrl = artist.artworkUrl,
                                isCircle = true,
                                cardWidth = 160.dp,
                                onClick = { onOpenArtist(artist.name) },
                            )
                        }
                    }
                }
            }
        }

        // Section 7: Top Albums
        if (topAlbums.isNotEmpty()) {
            item {
                TvSectionRow(
                    title = "Top Albums",
                    subtitle = "Albums in heavy rotation",
                ) {
                    itemsIndexed(topAlbums.take(12), key = { _, album -> "album_${album.artist}_${album.name}" }) { _, album ->
                        TvMusicCard(
                            title = album.name,
                            subtitle = album.artist,
                            imageUrl = album.artworkUrl,
                            cardWidth = 176.dp,
                            onClick = { onOpenAlbum(album.name, album.artist) },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun TvActionChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .tvFocusHighlight(scale = 1.08f, shape = RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
