# PR Plan: Stop LastWave from Dropping and Re-ranking YTM Home

## Goal
Show YouTube Music's real Home shelves (original titles, original order, original items) on LastWave's Home feed for both logged-in users and guests. Remove the code that deletes YTM songs when authenticated and scrambles ranking with random noise.

## Scope of Changes
1. **Model (`HomeShelf.kt`)**: Define `HomeShelf` and `HomeShelfItem` (`Track`, `Playlist`).
2. **InnerTube API (`InnerTubeMusicApi.kt`)**:
   - `parseCarouselHeader`: Extracts shelf titles and subtitles from `musicCarouselShelfBasicHeaderRenderer`.
   - `parseCarouselShelf`: Parses items inside each `musicCarouselShelfRenderer` (`musicTwoRowItemRenderer`, `musicResponsiveListItemRenderer`), skipping items with no usable id.
   - `fetchHomeShelves`: Walks `contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents` and returns shelves in YTM's exact order.
3. **Repository (`FeedRepository.kt`)**:
   - In `loadFeed()`, remove `if (isYtConnected) emptyList()` so native home is fetched when authenticated.
   - In `trackScore()`, remove `jitter * 35.0` so scoring is deterministic.
   - Expose `nativeShelves: List<HomeShelf>` in `FeedData` and in-memory cache with graceful fallback.
4. **UI (`FeedScreen.kt`)**:
   - Render native shelves first in YTM order using existing `FeedSectionHeader` and `FeedMediaRow` with `FeedMediaCard`.
   - Reuse existing playback and playlist navigation handlers.
   - Hide empty shelves.
   - Preserve pull-to-refresh behavior.
5. **Testing**:
   - Unit tests in `HomeShelfParserTest.kt` verifying shelf ordering, title/subtitle extraction, item parsing, and skip rules.

## Intentionally NOT Changed (Out of Scope)
- Radio / queue logic in `MusicPlayer.kt`.
- Telemetry and history pings.
- Explore, Moods & Genres hubs.
- Home chips (`chipCloudChipRenderer`).
- Home continuation / pagination.
- Speed Dial, Last.fm scrobbling, search, lyrics, UI outside the Home feed.

## Attribution
Portions of the InnerTube home carousel navigation pattern adapted from Metrolist (`HomePage.kt`), licensed under GNU General Public License v3.0 (GPL-3.0).
