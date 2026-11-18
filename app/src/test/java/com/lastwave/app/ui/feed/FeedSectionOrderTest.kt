package com.lastwave.app.ui.feed

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.feed.FeedData
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.YouTubeMusicTrack
import org.junit.Test

class FeedSectionOrderTest {
    private fun shelf(title: String, videoId: String) = HomeShelf(
        title = title,
        items = listOf(HomeShelfItem.Track(YouTubeMusicTrack(videoId, "Song", "Artist"))),
    )

    @Test
    fun refreshUpdatesMatchingShelvesWithoutRemovingOrReorderingExistingOnes() {
        val old = listOf(shelf("First", "first"), shelf("Second", "old"))
        val updatedSecond = shelf(" second ", "updated")
        val added = shelf("New", "new")

        assertThat(mergeFeedShelves(old, listOf(updatedSecond, added)))
            .containsExactly(old[0], updatedSecond.copy(title = "Second"), added).inOrder()
        assertThat(mergeFeedShelves(old, listOf(HomeShelf("Second"))))
            .containsExactlyElementsIn(old).inOrder()
    }

    @Test
    fun refreshRetainsMissingSectionsAndUsesFreshContentWhenAvailable() {
        val oldTrack = YouTubeMusicTrack("old", "Old", "Artist")
        val newTrack = YouTubeMusicTrack("new", "New", "Artist")
        val old = FeedData(userName = "user", nativeShelves = listOf(shelf("Old", "old")),
            quickPicks = listOf(oldTrack), charts = listOf(oldTrack), lastUpdatedMillis = 1)
        val fresh = FeedData(userName = "user", quickPicks = listOf(newTrack), lastUpdatedMillis = 2)

        val (visible, _) = retainFeedSections(old, emptyList(), fresh)
        assertThat(visible.nativeShelves).isEqualTo(old.nativeShelves)
        assertThat(visible.charts).containsExactly(oldTrack)
        assertThat(visible.quickPicks).containsExactly(newTrack)
        assertThat(visible.lastUpdatedMillis).isEqualTo(2)
    }

    @Test
    fun refreshKeepsPagedShelvesInPlaceAndDoesNotPromoteThem() {
        val first = shelf("First", "first")
        val oldPage = shelf("Paged", "old")
        val updatedPage = shelf("Paged", "updated")
        val newShelf = shelf("New", "new")
        val old = FeedData(userName = "user", nativeShelves = listOf(first))
        val fresh = FeedData(userName = "user", nativeShelves = listOf(updatedPage, newShelf))

        val (visible, pages) = retainFeedSections(old, listOf(oldPage), fresh)
        assertThat(visible.nativeShelves).containsExactly(first)
        assertThat(pages).containsExactly(updatedPage, newShelf).inOrder()
    }

    @Test
    fun accountChangeDoesNotRetainPreviousSectionsOrPages() {
        val old = FeedData(userName = "first", nativeShelves = listOf(shelf("Old", "old")))
        val fresh = FeedData(userName = "second", nativeShelves = listOf(shelf("New", "new")))

        val (visible, pages) = retainFeedSections(old, listOf(shelf("Page", "page")), fresh)
        assertThat(visible).isEqualTo(fresh)
        assertThat(pages).isEmpty()
    }

    @Test
    fun freshCombinedSectionsDoNotIncludeRetainedNativeItems() {
        val old = FeedData(nativeShelves = listOf(shelf("Quick picks", "old"), shelf("New releases", "old-release")))
        val freshTrack = YouTubeMusicTrack("fresh", "Fresh", "Artist")
        val freshRelease = com.lastwave.app.data.music.YouTubePlaylistSummary("fresh-release", "Fresh release")
        val fresh = FeedData(quickPicks = listOf(freshTrack), newReleases = listOf(freshRelease))
        val (layout, _) = retainFeedSections(old, emptyList(), fresh)

        assertThat(feedQuickPicks(layout).map { it.videoId }).contains("old")
        assertThat(feedQuickPicks(fresh)).containsExactly(freshTrack)
        assertThat(feedNewReleases(fresh)).containsExactly(freshRelease)
    }
}
