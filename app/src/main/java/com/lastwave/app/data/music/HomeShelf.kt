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

    val cardData: HomeCardData?
        get() = null

    @Immutable
    data class Track(
        val track: YouTubeMusicTrack,
        override val aspectRatio: String? = null,
        override val itemType: String? = null,
        override val caption: String? = null,
        override val cardData: HomeCardData? = null,
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
        override val cardData: HomeCardData? = null,
    ) : HomeShelfItem

    @Immutable
    data class PodcastEpisode(
        val videoId: String,
        val title: String,
        val showName: String? = null,
        val showBrowseId: String? = null,
        val subtitle: String? = null,
        val durationText: String? = null,
        val progressFraction: Float? = null,
        val artworkUrl: String? = null,
        val thumbnailUrls: List<String> = emptyList(),
        override val aspectRatio: String? = "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9",
        override val itemType: String? = "podcast_episode",
        override val caption: String? = null,
        override val cardData: HomeCardData? = null,
    ) : HomeShelfItem

    @Immutable
    data class PodcastShow(
        val browseId: String,
        val title: String,
        val author: String? = null,
        val artworkUrl: String? = null,
        override val aspectRatio: String? = "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
        override val itemType: String? = "podcast_show",
        override val caption: String? = null,
        override val cardData: HomeCardData? = null,
    ) : HomeShelfItem
}

fun HomeShelfItem.PodcastEpisode.toDomainEpisode(): com.lastwave.app.data.podcast.PodcastEpisode =
    com.lastwave.app.data.podcast.PodcastEpisode(
        videoId = videoId,
        title = title,
        showId = showBrowseId,
        showTitle = showName,
        artworkUrl = artworkUrl,
        publishedLabel = subtitle,
    )

fun HomeShelfItem.PodcastShow.toDomainShow(): com.lastwave.app.data.podcast.PodcastShow =
    com.lastwave.app.data.podcast.PodcastShow(
        showId = browseId,
        title = title,
        publisher = author,
        artworkUrl = artworkUrl,
    )

fun HomeShelfItem.PodcastEpisode.toPlayableTrack(): com.lastwave.app.playback.PlayableTrack =
    com.lastwave.app.playback.PlayableTrack(
        title = title,
        artist = showName?.takeIf { it.isNotBlank() } ?: "Podcast",
        album = null,
        artworkUrl = artworkUrl,
        videoId = videoId,
        mediaType = com.lastwave.app.playback.MediaType.PODCAST_EPISODE,
        showId = showBrowseId,
    )

fun HomeShelfItem.PodcastEpisode.toYouTubeMusicTrack(): YouTubeMusicTrack =
    YouTubeMusicTrack(
        videoId = videoId,
        title = title,
        artist = showName?.takeIf { it.isNotBlank() } ?: "Podcast",
        album = null,
        artworkUrl = artworkUrl,
    )

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
    val playAllButton: HomePlayAllButton? = null,
    /** Includes unsupported source items, so a partially parsed shelf cannot qualify as daily. */
    val sourceItemCount: Int? = null,
)
