package com.lastwave.app.ui.feed

import com.lastwave.app.data.feed.FeedData
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary

internal val feedSectionsAwaitingData = setOf(
    "quick_picks", "because_you_listen_to", "fresh_finds", "jump_back_in",
    "mixed_for_you", "mixes", "spotlight_hero", "spotlight", "top_artists",
    "heavy_rotation", "albums_in_rotation", "albums", "trending_charts", "charts",
    "new_releases", "friends_activity", "friends", "podcast_shelf", "podcasts",
)

internal class FeedSectionOrder {
    private var visible = emptyList<String>()

    fun update(available: List<String>, ready: List<String>): List<String> {
        val availableKeys = available.toSet()
        val retained = visible.filter { it in availableKeys }
        val retainedKeys = retained.toSet()
        visible = retained + ready.filterNot { it in retainedKeys }
        return visible
    }
}

internal fun readyFeedSections(
    ordered: List<String>,
    available: Set<String>,
    isPending: (String) -> Boolean,
): List<String> = buildList {
    for (key in ordered) {
        if (key in available) add(key)
        else if (isPending(key)) break
    }
}

internal fun mergeFeedShelves(previous: List<HomeShelf>, fresh: List<HomeShelf>): List<HomeShelf> {
    val previousTitles = previous.map { it.title.trim().lowercase() }.toSet()
    val freshByTitle = fresh.filter { it.items.isNotEmpty() }.associateBy { it.title.trim().lowercase() }
    return previous.map { old -> freshByTitle[old.title.trim().lowercase()]?.copy(title = old.title) ?: old } +
        fresh.filter { it.items.isNotEmpty() && it.title.trim().lowercase() !in previousTitles }
}

internal fun hasFreshFeedSection(
    key: String,
    fresh: FeedData?,
    freshPagedTitles: Set<String>,
    shelfTitle: String? = null,
): Boolean {
    if (fresh == null) return true
    fun freshNative(title: String?): Boolean {
        val normalized = title?.trim()?.lowercase() ?: return false
        return normalized in freshPagedTitles || fresh.nativeShelves.any {
            it.title.trim().lowercase() == normalized && it.items.isNotEmpty()
        }
    }
    return when (key) {
        "infinite_radio", "quick_picks" -> feedQuickPicks(fresh).isNotEmpty()
        "quick_tiles" -> fresh.quickTiles.isNotEmpty()
        "because_you_listen_to" -> fresh.becauseYouListenTo?.items?.isNotEmpty() == true
        "fresh_finds" -> fresh.freshFinds.isNotEmpty()
        "jump_back_in" -> fresh.jumpBackIn.isNotEmpty()
        "mixed_for_you", "mixes" -> fresh.mixes.isNotEmpty()
        "spotlight_hero", "spotlight" -> fresh.spotlight != null
        "top_artists" -> fresh.topArtists.isNotEmpty()
        "heavy_rotation" -> fresh.heavyRotation.isNotEmpty()
        "albums_in_rotation", "albums" -> fresh.recentAlbums.isNotEmpty()
        "trending_charts", "charts" -> fresh.charts.isNotEmpty()
        "new_releases" -> feedNewReleases(fresh).isNotEmpty()
        "friends_activity", "friends" -> fresh.friends.isNotEmpty()
        "podcast_shelf", "podcasts" -> fresh.podcastShows.isNotEmpty()
        else -> if (key.startsWith("yt:") || key.startsWith("paged_shelf_")) freshNative(shelfTitle) else true
    }
}

internal fun feedQuickPicks(feed: FeedData): List<YouTubeMusicTrack> {
    val shelf = feed.nativeShelves.firstOrNull {
        it.title.contains("quick pick", ignoreCase = true) || it.title.contains("picked for you", ignoreCase = true)
    }
    val fromYt = shelf?.items?.filterIsInstance<HomeShelfItem.Track>()?.map { it.track }.orEmpty()
    return (feed.quickPicks + fromYt).distinctBy { it.videoId.ifBlank { it.title } }
}

internal fun feedNewReleases(feed: FeedData): List<YouTubePlaylistSummary> {
    val shelf = feed.nativeShelves.firstOrNull { it.title.contains("new release", ignoreCase = true) }
    val fromYt = shelf?.items?.mapNotNull { item ->
        when (item) {
            is HomeShelfItem.Playlist -> item.playlist
            is HomeShelfItem.Track -> YouTubePlaylistSummary(
                id = item.track.videoId, title = item.track.title, author = item.track.artist,
                artworkUrl = item.track.artworkUrl,
            )
            else -> null
        }
    }.orEmpty()
    return (feed.newReleases + fromYt).distinctBy { it.id.ifBlank { it.title } }
}

// The repository keeps the fresh feed; this only preserves the current screen's layout.
internal fun retainFeedSections(
    previous: FeedData,
    pagedShelves: List<HomeShelf>,
    fresh: FeedData,
): Pair<FeedData, List<HomeShelf>> {
    if (previous.isYtConnected != fresh.isYtConnected || previous.ytAccountName != fresh.ytAccountName ||
        previous.userName != fresh.userName
    ) return fresh to emptyList()

    val nativeTitles = previous.nativeShelves.map { it.title.trim().lowercase() }.toSet()
    val freshNative = if (pagedShelves.isEmpty()) fresh.nativeShelves else
        fresh.nativeShelves.filter { it.title.trim().lowercase() in nativeTitles }
    val newPaged = if (pagedShelves.isEmpty()) pagedShelves else
        mergeFeedShelves(pagedShelves, fresh.nativeShelves.filter { it.title.trim().lowercase() !in nativeTitles })

    return fresh.copy(
        nativeShelves = mergeFeedShelves(previous.nativeShelves, freshNative),
        spotlight = fresh.spotlight ?: previous.spotlight,
        quickTiles = fresh.quickTiles.ifEmpty { previous.quickTiles },
        quickPicks = fresh.quickPicks.ifEmpty { previous.quickPicks },
        newReleases = fresh.newReleases.ifEmpty { previous.newReleases },
        charts = fresh.charts.ifEmpty { previous.charts },
        mixes = fresh.mixes.ifEmpty { previous.mixes },
        jumpBackIn = fresh.jumpBackIn.ifEmpty { previous.jumpBackIn },
        recentAlbums = fresh.recentAlbums.ifEmpty { previous.recentAlbums },
        topArtists = fresh.topArtists.ifEmpty { previous.topArtists },
        heavyRotation = fresh.heavyRotation.ifEmpty { previous.heavyRotation },
        becauseYouListenTo = fresh.becauseYouListenTo?.takeIf { it.items.isNotEmpty() } ?: previous.becauseYouListenTo,
        freshFinds = fresh.freshFinds.ifEmpty { previous.freshFinds },
        friends = fresh.friends.ifEmpty { previous.friends },
        podcastShows = fresh.podcastShows.ifEmpty { previous.podcastShows },
        podcastShelfTitle = if (fresh.podcastShows.isEmpty()) previous.podcastShelfTitle else fresh.podcastShelfTitle,
    ) to newPaged
}
