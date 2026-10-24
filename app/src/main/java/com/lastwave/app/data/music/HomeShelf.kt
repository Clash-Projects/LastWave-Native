/*
 * Adapted from Metrolist's InnerTube home feed architecture (GPL-3.0).
 * Reference: com.metrolist.innertube.pages.HomePage
 * Copyright (C) 2024 Metrolist Project / LastWave Authors
 * Licensed under the GNU General Public License v3.0 (GPL-3.0).
 */
package com.lastwave.app.data.music

import androidx.compose.runtime.Immutable

/**
 * Represents an item in a native YouTube Music home carousel shelf.
 * Can be either a playable track or a playlist/album collection.
 */
@Immutable
sealed interface HomeShelfItem {
    /**
     * Visual/layout hint YTM sends on musicTwoRowItemRenderer, e.g.
     * MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE or ..._RECTANGLE_16_9.
     * Null when the renderer does not declare one (defaults to square).
     */
    val aspectRatio: String?
        get() = null

    /** Coarse item type derived from the navigation endpoint's page type. */
    val itemType: String?
        get() = null

    /** Caption YTM shows on the card, e.g. "Because you liked X". */
    val caption: String?
        get() = null

    @Immutable
    data class Track(
        val track: YouTubeMusicTrack,
        override val aspectRatio: String? = null,
        override val itemType: String? = null,
        override val caption: String? = null,
    ) : HomeShelfItem

    @Immutable
    data class Playlist(
        val playlist: YouTubePlaylistSummary,
        override val aspectRatio: String? = null,
        override val itemType: String? = null,
        override val caption: String? = null,
        /** Collage covers + preview song rows when the shelf carries them. */
        val collageArtworkUrls: List<String> = emptyList(),
        val previewSongs: List<YouTubeMusicTrack> = emptyList(),
    ) : HomeShelfItem
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
    /** Uppercase eyebrow text from the shelf header strapline (e.g. "NEW RELEASE 'X'"). */
    val strapline: String? = null,
    /** Browse endpoint id for the shelf's "more" chevron, when YTM provides one. */
    val moreBrowseId: String? = null,
)
