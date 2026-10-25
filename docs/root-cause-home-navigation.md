# Root Cause Analysis: Home Navigation (Back From Chips & Feed Button)

## Bug A: Pressing Back After Selecting a Chip Exits the App
### Root Cause
In `MainShell.kt`, the root `PredictiveBackScreen` handler is conditionally enabled only when the user is on a non-Feed tab (`tabs[page] != MainTab.FEED`). Inside `FeedScreen.kt`, there was no `BackHandler` registered for the chip filtering state (`state.selectedChip`).

When a user taps a Home chip (e.g. Relax, Podcasts, Energize), `FeedViewModel.selectChip(chip)` sets `selectedChip` and filters the view to the chip's shelves. However, when the user subsequently presses the system back button:
1. `FeedScreen` had no active back handler.
2. The root `NavHost` (`LastWaveNavHost`) had `Screen.MainShell` as its top-level destination with no previous screen to pop (as splash had been cleared).
3. The back event fell through to the hosting Android Activity (`MainActivity`), which interpreted it as exiting the application.

### Fix
Add a `BackHandler` inside `FeedScreen.kt` enabled strictly when `state.selectedChip != null`. On back, it calls the existing `viewModel.selectChip(null)` to deselect the chip and restore the normal Home feed while retaining scroll state. It yields priority to overlays, dialogs, search, and the full player sheet.

---

## Bug B: Feed Button in Bottom Bar is Unresponsive in Multiple Scenarios
### Root Cause
In `MainShell.kt`, `FloatingNavBar`'s `onSelect` callback only triggered `pagerState.animateScrollToPage(index)` within `MainShell`'s `HorizontalPager`. This caused two distinct failures:
1. **Detail Screens Opened From Home**: Screens like `AlbumDetail`, `ArtistDetail`, and `PlaylistDetail` are destinations pushed on `navController` on top of `Screen.MainShell`. Because `bottomNavSlot` (in `PlayerHost.kt`) rendered the bottom bar across routes, tapping the Feed button only invoked `MainShell`'s internal pager without interacting with `navController`. It never popped the detail destinations, leaving the user stuck on the detail screen.
2. **Already on Home Feed**: When `selectedTabIndex == feedIndex`, `MainShell` did not reselect-scroll or trigger a refresh because the pager was already on page 0. It lacked any communication channel to `FeedScreen`'s `LazyListState` to scroll to top, clear chips, or trigger `viewModel.refresh()`.
3. **No Debounce / Re-entrancy Protection**: There was no debounce or in-progress refresh guard to prevent double-refresh requests on rapid taps.

### Fix
1. Provide an `onReturnToHome` coordinator from `NavGraph.kt` to `MainShell.kt` that pops detail screens on `navController` back to `Screen.MainShell` when tapped from a detail screen.
2. When switching from another tab (Stats, Playlists), instantly return to Home via `pagerState.scrollToPage(feedIndex)` preserving existing scroll and shelves.
3. Expose a pure decision function `resolveFeedTapAction` handling:
   - `DeselectChipAndRefresh`: clears active chip, animates `LazyListState` to 0, then calls `viewModel.refresh()`.
   - `ScrollTopAndRefresh`: animates `LazyListState` to 0, then calls `viewModel.refresh()`.
   - `RefreshOnly`: triggers `viewModel.refresh()` immediately.
   - `NavigateToFeed`: returns to Home from another tab or detail screen.
   - `Ignore`: guards against double-refresh during in-progress refresh and enforces a 600 ms debounce window.
