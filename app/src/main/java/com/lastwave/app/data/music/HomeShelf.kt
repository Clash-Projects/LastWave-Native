package com.lastwave.app.data.music

import androidx.compose.runtime.Immutable

/**
 * Represents an item in a native YouTube Music home carousel shelf.
 * Can be either a playable track or a playlist/album collection.
 */
@Immutable
sealed interface HomeShelfItem {
    @Immutable
    data class Track(val track: YouTubeMusicTrack) : HomeShelfItem

    @Immutable
    data class Playlist(val playlist: YouTubePlaylistSummary) : HomeShelfItem
}

/**
 * A native YouTube Music Home shelf preserving YTM's exact title, optional subtitle/strapline,
 * and ordered items as returned by InnerTube.
 */
@Immutable
data class HomeShelf(
    val title: String,
    val subtitle: String? = null,
    val items: List<HomeShelfItem> = emptyList(),
)
