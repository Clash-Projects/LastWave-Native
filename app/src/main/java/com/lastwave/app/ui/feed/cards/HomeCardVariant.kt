/*
 * Card-variant selection for native YouTube Music Home shelves.
 * Pure function mapping a HomeShelfItem to the visual card that matches its
 * real YTM data. Unknown/missing hints always fall back to SQUARE.
 */
package com.lastwave.app.ui.feed.cards

import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.isCommunityPlaylistItem
import com.lastwave.app.data.music.isDailyDiscoverShelf
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
    /** Large 16:9 episode card for podcast category shelves. */
    PODCAST_EPISODE,
}

/**
 * Picks the card variant for a single shelf item from its real fields.
 * Community requires a playlist and user-channel author endpoint. Daily cards
 * additionally require the shelf's Play all watch endpoint; captions are not evidence.
 */
fun selectCardVariant(item: HomeShelfItem, shelf: HomeShelf? = null): HomeCardVariant {
    if (shelf != null && isDailyDiscoverShelf(shelf) && item in shelf.items) return HomeCardVariant.FEATURED
    return when (item) {
        is HomeShelfItem.Playlist -> selectPlaylistVariant(item)
        is HomeShelfItem.Track -> selectTrackVariant(item)
        is HomeShelfItem.PodcastEpisode -> HomeCardVariant.PODCAST_EPISODE
        is HomeShelfItem.PodcastShow -> HomeCardVariant.SQUARE
    }
}

private fun selectPlaylistVariant(item: HomeShelfItem.Playlist): HomeCardVariant {
    if (isCommunityPlaylistItem(item)) {
        return HomeCardVariant.COMMUNITY
    }
    if (isWideAspect(item.aspectRatio)) return HomeCardVariant.WIDE
    return HomeCardVariant.SQUARE
}

private fun selectTrackVariant(item: HomeShelfItem.Track): HomeCardVariant {
    if (isWideAspect(item.aspectRatio)) return HomeCardVariant.WIDE
    return HomeCardVariant.SQUARE
}

/**
 * The header and renderer evidence together identify a daily-discover carousel.
 */
fun isFeaturedShelf(shelf: HomeShelf): Boolean = isDailyDiscoverShelf(shelf)

/** True when the shelf's items are playable tracks (drives the "Play all" pill). */
fun shelfIsPlayableTracks(items: List<HomeShelfItem>): Boolean =
    items.isNotEmpty() && items.all { it is HomeShelfItem.Track }
