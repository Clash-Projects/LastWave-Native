package com.lastwave.app.ui.feed

import com.lastwave.app.data.feed.FeedData
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.YouTubeMusicTrack

// Project from the original feed so restoring exclusions also restores cached recommendations.
internal fun FeedUiState.withRecommendationExclusions(keys: Set<String>): FeedUiState {
    if (keys.isEmpty()) return this
    fun allowed(track: YouTubeMusicTrack) = GeneratedTrack(track.title, track.artist).key !in keys
    fun shelves(shelves: List<HomeShelf>) = shelves.map { shelf ->
        shelf.copy(items = shelf.items.filterNot { it is HomeShelfItem.Track && !allowed(it.track) })
    }
    fun FeedData.filtered() = copy(
        nativeShelves = shelves(nativeShelves),
        quickPicks = quickPicks.filter(::allowed),
        mixes = mixes.filter { allowed(it.seed) },
        becauseYouListenTo = becauseYouListenTo?.let { it.copy(items = it.items.filter(::allowed)) },
        freshFinds = freshFinds.filter(::allowed),
    )
    return copy(
        feedData = feedData.filtered(),
        lastCompletedFeed = lastCompletedFeed?.filtered(),
        pagedShelves = shelves(pagedShelves),
        chipShelves = shelves(chipShelves),
    )
}
