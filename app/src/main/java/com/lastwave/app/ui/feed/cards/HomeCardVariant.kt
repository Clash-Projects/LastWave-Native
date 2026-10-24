/*
 * Card-variant selection for native YouTube Music Home shelves.
 * Pure function mapping a HomeShelfItem to the visual card that matches its
 * real YTM data. Unknown/missing hints always fall back to SQUARE.
 */
package com.lastwave.app.ui.feed.cards

import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.isWideAspect

/** Visual variants supported by the native shelf card system. */
enum class HomeCardVariant {
    /** Default square cover + title + info line. Songs, albums, playlists. */
    SQUARE,
    /** 16:9 thumbnail + title + info ("Channel • 73M views"). Videos. */
    WIDE,
    /** Big square featured card with caption + scrim. "Your daily discover". */
    FEATURED,
    /** Collage cover + title + up to 3 song rows. Community playlists. */
    COMMUNITY,
}

/**
 * Picks the card variant for a single shelf item from its real fields.
 * Order matters: community (rich playlist) wins over wide, which wins over
 * featured, which wins over square.
 */
fun selectCardVariant(item: HomeShelfItem): HomeCardVariant {
    return when (item) {
        is HomeShelfItem.Playlist -> selectPlaylistVariant(item)
        is HomeShelfItem.Track -> selectTrackVariant(item)
    }
}

private fun selectPlaylistVariant(item: HomeShelfItem.Playlist): HomeCardVariant {
    // Collage + song rows only when YTM supplied the extra data.
    if (item.previewSongs.isNotEmpty() || item.collageArtworkUrls.size >= 2) {
        return HomeCardVariant.COMMUNITY
    }
    if (isWideAspect(item.aspectRatio)) return HomeCardVariant.WIDE
    if (!item.caption.isNullOrBlank()) return HomeCardVariant.FEATURED
    return HomeCardVariant.SQUARE
}

private fun selectTrackVariant(item: HomeShelfItem.Track): HomeCardVariant {
    if (isWideAspect(item.aspectRatio)) return HomeCardVariant.WIDE
    if (!item.caption.isNullOrBlank()) return HomeCardVariant.FEATURED
    return HomeCardVariant.SQUARE
}

/**
 * Returns true when every item in a shelf resolves to [HomeCardVariant.FEATURED]
 * — the big snapping "Your daily discover"-style carousel.
 */
fun isFeaturedShelf(items: List<HomeShelfItem>): Boolean =
    items.isNotEmpty() && items.all { selectCardVariant(it) == HomeCardVariant.FEATURED }

/** True when the shelf's items are playable tracks (drives the "Play all" pill). */
fun shelfIsPlayableTracks(items: List<HomeShelfItem>): Boolean =
    items.isNotEmpty() && items.all { it is HomeShelfItem.Track }
