package com.lastwave.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedNavDecisionTest {

    // --- Back from chip tests ---

    @Test
    fun backWithChipSelectedReturnsDeselectChip() {
        val action = resolveFeedBackAction(isChipSelected = true)
        assertEquals(FeedBackAction.DeselectChip, action)
    }

    @Test
    fun backWithNoChipSelectedReturnsPassThrough() {
        val action = resolveFeedBackAction(isChipSelected = false)
        assertEquals(FeedBackAction.PassThrough, action)
    }

    // --- Feed button tap tests ---

    @Test
    fun feedTapFromOtherTabOrDetailScreenReturnsNavigateToFeed() {
        val fromOtherTab = resolveFeedTapAction(
            isChipSelected = false,
            isAtTop = false,
            isOnHomeFeed = false,
            isRefreshing = false,
        )
        assertEquals(FeedTapAction.NavigateToFeed, fromOtherTab)

        val fromAlbumScreen = resolveFeedTapAction(
            isChipSelected = false,
            isAtTop = true,
            currentDestination = "album_detail/Thriller?artistName=Michael+Jackson",
            isRefreshing = false,
        )
        assertEquals(FeedTapAction.NavigateToFeed, fromAlbumScreen)

        val fromArtistScreen = resolveFeedTapAction(
            isChipSelected = true,
            isAtTop = true,
            currentDestination = "artist_detail/Radiohead",
            isRefreshing = false,
        )
        assertEquals(FeedTapAction.NavigateToFeed, fromArtistScreen)
    }

    @Test
    fun feedTapFromOtherScreenIgnoresRefreshInProgress() {
        // Leaving another screen must return to feed even if background refresh was active
        val action = resolveFeedTapAction(
            isChipSelected = false,
            isAtTop = true,
            isOnHomeFeed = false,
            isRefreshing = true,
        )
        assertEquals(FeedTapAction.NavigateToFeed, action)
    }

    @Test
    fun feedTapOnHomeScrolledDownReturnsScrollTopAndRefresh() {
        val action = resolveFeedTapAction(
            isChipSelected = false,
            isAtTop = false,
            isOnHomeFeed = true,
            isRefreshing = false,
            nowMs = 1000L,
            lastTapMs = 0L,
        )
        assertEquals(FeedTapAction.ScrollTopAndRefresh, action)
    }

    @Test
    fun feedTapOnHomeWithChipSelectedReturnsDeselectChipAndRefresh() {
        val whenScrolledDown = resolveFeedTapAction(
            isChipSelected = true,
            isAtTop = false,
            isOnHomeFeed = true,
            isRefreshing = false,
            nowMs = 1000L,
            lastTapMs = 0L,
        )
        assertEquals(FeedTapAction.DeselectChipAndRefresh, whenScrolledDown)

        val whenAtTop = resolveFeedTapAction(
            isChipSelected = true,
            isAtTop = true,
            isOnHomeFeed = true,
            isRefreshing = false,
            nowMs = 1000L,
            lastTapMs = 0L,
        )
        assertEquals(FeedTapAction.DeselectChipAndRefresh, whenAtTop)
    }

    @Test
    fun feedTapOnHomeAtTopWithNoChipReturnsRefreshOnly() {
        val action = resolveFeedTapAction(
            isChipSelected = false,
            isAtTop = true,
            isOnHomeFeed = true,
            isRefreshing = false,
            nowMs = 1000L,
            lastTapMs = 0L,
        )
        assertEquals(FeedTapAction.RefreshOnly, action)
    }

    @Test
    fun feedTapIgnoredWhenRefreshInProgress() {
        val action = resolveFeedTapAction(
            isChipSelected = false,
            isAtTop = true,
            isOnHomeFeed = true,
            isRefreshing = true,
            nowMs = 1000L,
            lastTapMs = 0L,
        )
        assertEquals(FeedTapAction.Ignore, action)
    }

    @Test
    fun feedTapIgnoredWithinDebounceWindow() {
        val tap1Time = 1000L
        val tap2Time = 1400L // 400ms later, within 600ms cooldown

        val action = resolveFeedTapAction(
            isChipSelected = false,
            isAtTop = false,
            isOnHomeFeed = true,
            isRefreshing = false,
            nowMs = tap2Time,
            lastTapMs = tap1Time,
            debounceMs = 600L,
        )
        assertEquals(FeedTapAction.Ignore, action)
    }

    @Test
    fun feedTapAllowedAfterDebounceWindowExpires() {
        val tap1Time = 1000L
        val tap2Time = 1601L // 601ms later, cooldown has passed

        val action = resolveFeedTapAction(
            isChipSelected = false,
            isAtTop = false,
            isOnHomeFeed = true,
            isRefreshing = false,
            nowMs = tap2Time,
            lastTapMs = tap1Time,
            debounceMs = 600L,
        )
        assertEquals(FeedTapAction.ScrollTopAndRefresh, action)
    }
}
