package com.lastwave.app.ui.navigation

/**
 * Action resolved when the Feed button in the bottom navigation bar is tapped.
 */
sealed interface FeedTapAction {
    /** Already on Home with a chip selected: clear chip, scroll to top, then refresh. */
    data object DeselectChipAndRefresh : FeedTapAction

    /** Already on Home and scrolled down: animate scroll to top, then refresh. */
    data object ScrollTopAndRefresh : FeedTapAction

    /** Already on Home at the top: refresh feed immediately. */
    data object RefreshOnly : FeedTapAction

    /** From another tab or detail screen: return to Home preserving scroll and content. */
    data object NavigateToFeed : FeedTapAction

    /** Ignore tap (refresh in progress or debounced within cooldown window). */
    data object Ignore : FeedTapAction
}

/**
 * Action resolved when the system back button is pressed while on the Feed screen.
 */
sealed interface FeedBackAction {
    /** Chip is active: deselect chip and return to normal Home feed. */
    data object DeselectChip : FeedBackAction

    /** No chip active: pass back through to system / parent handler (e.g. exit app). */
    data object PassThrough : FeedBackAction
}

/** Default minimum interval in milliseconds between successive Feed-tap refreshes. */
const val FEED_TAP_DEBOUNCE_MS: Long = 600L

/**
 * Pure decision function determining the back action on the Feed screen.
 */
fun resolveFeedBackAction(isChipSelected: Boolean): FeedBackAction =
    if (isChipSelected) FeedBackAction.DeselectChip else FeedBackAction.PassThrough

/**
 * Pure decision function determining the action for a Feed button tap.
 *
 * @param isChipSelected True if a mood/activity chip is currently active on Home.
 * @param isAtTop True if the Home feed is scrolled to the very top.
 * @param currentDestination Identifier for the current destination (e.g. "feed", "main_shell", "album_detail").
 * @param isRefreshing True if a feed refresh is currently in progress.
 * @param nowMs Current epoch time in milliseconds.
 * @param lastTapMs Timestamp in milliseconds of the last accepted Feed-tap refresh.
 * @param debounceMs Debounce cooldown window in milliseconds (defaults to 600 ms).
 */
fun resolveFeedTapAction(
    isChipSelected: Boolean,
    isAtTop: Boolean,
    currentDestination: String?,
    isRefreshing: Boolean,
    nowMs: Long = 0L,
    lastTapMs: Long = 0L,
    debounceMs: Long = FEED_TAP_DEBOUNCE_MS,
): FeedTapAction {
    val isOnHomeFeed = currentDestination == "feed" ||
        currentDestination == Screen.MainShell.route ||
        currentDestination == "main_shell/feed"
    return resolveFeedTapAction(
        isChipSelected = isChipSelected,
        isAtTop = isAtTop,
        isOnHomeFeed = isOnHomeFeed,
        isRefreshing = isRefreshing,
        nowMs = nowMs,
        lastTapMs = lastTapMs,
        debounceMs = debounceMs,
    )
}

/**
 * Pure decision function determining the action for a Feed button tap.
 *
 * @param isChipSelected True if a mood/activity chip is currently active on Home.
 * @param isAtTop True if the Home feed is scrolled to the very top.
 * @param isOnHomeFeed True if the user is currently on the Home feed tab (not another tab or detail screen).
 * @param isRefreshing True if a feed refresh is currently in progress.
 * @param nowMs Current epoch time in milliseconds.
 * @param lastTapMs Timestamp in milliseconds of the last accepted Feed-tap refresh.
 * @param debounceMs Debounce cooldown window in milliseconds (defaults to 600 ms).
 */
fun resolveFeedTapAction(
    isChipSelected: Boolean,
    isAtTop: Boolean,
    isOnHomeFeed: Boolean,
    isRefreshing: Boolean,
    nowMs: Long = 0L,
    lastTapMs: Long = 0L,
    debounceMs: Long = FEED_TAP_DEBOUNCE_MS,
): FeedTapAction {
    if (!isOnHomeFeed) {
        return FeedTapAction.NavigateToFeed
    }
    if (isRefreshing) {
        return FeedTapAction.Ignore
    }
    if (nowMs > 0L && lastTapMs > 0L && (nowMs - lastTapMs) < debounceMs) {
        return FeedTapAction.Ignore
    }
    return when {
        isChipSelected -> FeedTapAction.DeselectChipAndRefresh
        !isAtTop -> FeedTapAction.ScrollTopAndRefresh
        else -> FeedTapAction.RefreshOnly
    }
}
