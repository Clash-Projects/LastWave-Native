# Podcasts (YouTube Music) — Design

- Date: 2026-10-20
- Status: Draft for review
- Scope: Android app (`com.lastwave.app`)
- Source of content: YouTube Music InnerTube only (no RSS)

## Summary

Add podcast discovery and playback using YouTube Music's own podcast surfaces and
search, so podcasts follow the same catalog, ranking, and curation as YouTube
Music. The feature is controlled by exactly one new user setting, `Podcasts`,
which is enabled by default and hides every podcast surface when turned off.
Music behavior (radio, scrobbling, lyrics, taste profile, downloads) is not
applied to podcast episodes.

## Verified feasibility (probe, 2026-10-20)

A read-only InnerTube probe was run against a live WEB_REMIX session:

- `POST /youtubei/v1/browse` with `browseId = "FEmusic_podcasts"` returns the
  YouTube Music podcast landing page anonymously. It contains 19 carousels:
  `Popular shows` (24 show cards) plus `Popular episodes` and topic shelves
  (episodes).
- Show cards use `musicTwoRowItemRenderer` with
  `browseId = "MPSPPL<playlistId>"` and page type
  `MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE`. The subtitle is the publisher.
- Browsing a show ID returns a `musicResponsiveHeaderRenderer` (title,
  description, artwork) and a `musicShelfRenderer` of 20
  `musicMultiRowListItemRenderer` episodes. Episodes expose `title`, `subtitle`
  (views • published), `playbackProgress.durationText`, and a `videoId` through
  `onTap.watchEndpoint`.
- The show shelf has a `nextContinuationData` token. A continuation request
  returned 100 more episodes plus another token, so full archives are paginated.
- Search filter chips include `Podcasts` and `Episodes`. The observed tokens
  were:
  - Podcasts: `EgWKAQJQAWoSEBEQAxAQEAUQChAOEAkQBBAV`
  - Episodes: `EgWKAQJIAWoSEBEQAxAQEAUQChAOEAkQBBAV`
- The `Episodes` search returns `musicResponsiveListItemRenderer` rows with
  `flexColumns = [episode title, "18h ago • Show name"]`, `playlistItemData.videoId`,
  and an `MPED<videoId>` browse ID. The `Podcasts` search filter returns channels
  (`UC...`, `MUSIC_PAGE_TYPE_USER_CHANNEL`), not show pages, so it is not used.
- Episode playback resolves through the existing player path with the
  `VISIONOS` and `ANDROID_VR` clients (audio formats present). The tested episode
  returned `UNPLAYABLE` with the `WEB_REMIX` player client, so no WEB_REMIX-only
  playback path is added.

The probe confirms this is a UI + parsing + queue-guard feature, not a new
playback engine.

## Goals

1. Discover podcasts from YouTube Music's podcast landing page and episode search.
2. Open a show, browse its episodes, and play them in YouTube order.
3. Resume an episode where the user stopped and show completed state.
4. Add exactly one setting to turn podcast surfaces off.
5. Keep existing music behavior untouched for songs.

## Non-goals (v1)

- RSS/Atom feeds or third-party podcast directories.
- Subscriptions/following shows.
- Downloading episodes for offline playback.
- Video podcast playback; audio only.
- Chapters, transcripts, or podcast-specific speed/skip-silence controls.
- Global Search tab for podcasts; episode search lives inside the Podcasts screen.
- Android Auto podcast browsing; the current queue/notification surfaces still work.
- Backing up podcast progress.

## User-facing behavior

### The single setting

Add one boolean to `MiscSettings`:

```kotlin
val podcastsEnabled: Boolean = true
```

- Location: **Settings → Library → Content → Podcasts** (one
  `SettingsToggleCard`).
- Subtitle when on: "Show podcast browsing and episode search".
- Subtitle when off: "Podcasts hidden".
- Default: on.
- Off means:
  - The Home podcast shelf is hidden.
  - The Podcasts and Podcast Show screens pop if currently open.
  - No new podcast browse/search requests are made.
  - Saved episode progress is retained.
  - A podcast episode that is already playing continues; the toggle is a
    discovery/UI switch, not a playback kill switch.
- On again restores the Home shelf on the next feed refresh and keeps progress.

No other new user-visible podcast setting is introduced.

### Home

When `podcastsEnabled` is true and the podcast landing request succeeds, insert
one section below Quick Tiles (above Quick Picks):

- Title: "Podcasts".
- Horizontal row of show cards from the first show shelf (`Popular shows`).
- Each card shows artwork, show title, publisher.
- Tapping a card opens the Podcast Show screen.
- A "See all" action opens the Podcasts screen.
- The section is hidden when the toggle is off, the request fails, or no shows
  are returned. It is not added to `HomeSection`; the single Podcasts toggle is
  the only visibility control.

### Podcasts screen

Route: `podcasts`. Opened from Home's "See all".

- Top bar: back, "Podcasts", search action.
- Body: all shelves returned by `FEmusic_podcasts`, in YouTube order.
  - Show shelves render a horizontal row of show cards.
  - Episode shelves (`Popular episodes`, topic shelves) render compact horizontal
    episode cards with artwork, episode title, publisher/date line, and duration.
  - Tapping a show opens the show screen. Tapping an episode plays it as a
    single-item queue (no music radio).
- Search:
  - Opens an inline search field in the same screen.
  - Query runs YouTube Music's `Episodes` search.
  - Results are episode rows; tapping plays a single episode.
  - Clearing the query returns to the shelves.
  - Empty/error states are local to the screen and do not affect music.
- Loading, retry, and empty states follow existing Compose screen patterns.
- If the landing page is unavailable in the current region, show an explanatory
  empty state; the setting remains available.

### Podcast Show screen

Route: `podcast_show/{showId}`. Opened from Home or the Podcasts screen.

- Header: show artwork, title, publisher, expandable description.
- Actions:
  - `Play latest` starts the first loaded episode and queues the loaded episodes.
  - If a saved progress row exists for the show, show `Resume` for the most
    recently updated incomplete episode, starting at its saved position.
- Episode list:
  - Latest first, exactly as YouTube returns it.
  - Each row: artwork, title, published label, duration, local progress bar,
    played check.
  - Tapping a row starts playback at that row with the loaded episodes as the
    queue.
  - Scrolling to the end loads the next continuation page. A manual `Load more`
    affordance is shown if auto-load fails.
- No follow, download, like, lyrics, radio, or share-to-playlist actions for
  episodes in v1.

### Playback

- Episode -> `PlayableTrack` mapping:
  - `title` = episode title
  - `artist` = show title when known, otherwise `"Podcast"`
  - `album` = null
  - `artworkUrl` = episode thumbnail
  - `videoId` = YouTube video ID
  - `durationMs` = parsed duration when available
  - `mediaType = PODCAST_EPISODE`
  - `showId` = show ID when known
- Start with a new public `MusicPlayer.playPodcastQueue(tracks, startIndex,
  startPositionMs, sourceLabel)`:
  - Calls the existing queue path with `enableRadioContinuation = false`.
  - Sets `isEndlessQueue = false` and `radioQueueActive = false`.
  - Applies the saved position before preparing media.
- No radio:
  - `MusicPlayer.startRadioQueue` returns immediately when the seed is a podcast
    episode.
  - Queue enrichment/related-song continuation is skipped for podcast queues.
  - When the queue ends, playback stops unless the user loaded more episodes.
- Resume and completion:
  - Save the current episode position every 10 seconds while playing, and on
    pause, track change, seek, stop, and service teardown.
  - Ignore positions below 10 seconds on resume.
  - Resume only when position is below 95% of duration.
  - At >= 95% or `STATE_ENDED`, mark completed and reset the displayed progress.
- Notification/MediaSession/widget use the existing title/artist metadata; no
  new notification code is required.
- Speed, sleep timer, audio focus, and Bluetooth/USB routing continue to work.

### Excluded music behavior for episodes

- No Last.fm now-playing or scrobble.
- No lyrics fetch or lyrics UI network calls.
- No taste-profile, related-graph, or song-play-stat recording.
- No crossfade across podcast episodes; use the normal transition.
- No canvas fetch beyond ordinary artwork; episode artwork is static.
- No download action. The existing music context menu is not used for episodes.

## Architecture

### New package

`app/src/main/java/com/lastwave/app/data/podcast/`

- `PodcastModels.kt`
- `PodcastParser.kt` — pure parsing functions, unit-testable
- `PodcastRepository.kt` — caching, toggle gating, mapping
- `PodcastProgressStore.kt` — progress read/write facade

### Models

```kotlin
data class PodcastShow(
    val showId: String,          // MPSPPL...
    val playlistId: String?,     // PL...
    val title: String,
    val publisher: String?,
    val artworkUrl: String?,
    val description: String? = null,
)

data class PodcastEpisode(
    val videoId: String,
    val showId: String?,
    val showTitle: String?,
    val title: String,
    val artworkUrl: String?,
    val durationMs: Long?,
    val publishedLabel: String?,
    val description: String? = null,
)

data class PodcastShelf(
    val title: String,
    val shows: List<PodcastShow> = emptyList(),
    val episodes: List<PodcastEpisode> = emptyList(),
)

data class PodcastShowPage(
    val show: PodcastShow,
    val episodes: List<PodcastEpisode>,
    val continuation: String?,
)
```

`PodcastEpisode.toPlayableTrack()` is the only mapping used by UI and playback.

### InnerTube additions

Add to `InnerTubeMusicApi` (using the existing private `post`, `context`,
`browseRoot`, and `browseContinuation` helpers):

- `suspend fun fetchPodcastHome(): List<PodcastShelf>`
  - `browseId = "FEmusic_podcasts"` with `authenticated = ytAuth.connection.value.isConnected`.
  - On auth failure, retry once anonymously.
  - Parse through `PodcastParser.parseHome`.
- `suspend fun fetchPodcastShow(showId: String, continuation: String? = null): PodcastShowPage`
  - First page: `browse` with `browseId = showId`.
  - Later pages: `browse` with `continuation`.
  - Parse through `PodcastParser.parseShow`.
- `suspend fun searchPodcastEpisodes(query: String, limit: Int = 30): List<PodcastEpisode>`
  - Search with the `Episodes` filter.
  - Filter token resolution:
    1. Use the session-cached token.
    2. Otherwise use the observed fallback constant.
    3. If the filtered search is empty, perform one unfiltered search, parse the
       `Episodes` chip params from `chipCloudChipRenderer`, cache it, and retry
       once. If no chip exists, return the unfiltered rows that carry
       `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` or an `MPED` page type.
  - Parse through `PodcastParser.parseEpisodeSearch`.

Constants:

```kotlin
const val PODCASTS_BROWSE_ID = "FEmusic_podcasts"
const val PODCAST_EPISODES_SEARCH_FILTER = "EgWKAQJIAWoSEBEQAxAQEAUQChAOEAkQBBAV"
```

### Parsing rules

`PodcastParser` handles both landing and show pages defensively:

- Show card: `musicTwoRowItemRenderer`; title from `title`, publisher from
  `subtitle`, artwork from `thumbnail.musicThumbnailRenderer`, browse ID from
  `navigationEndpoint.browseEndpoint.browseId`; accept only `MPSPPL...`.
- Landing episode: `musicMultiRowListItemRenderer`; title from `title`, video ID
  from `onTap.watchEndpoint.videoId`, duration from
  `playbackProgress.musicPlaybackProgressRenderer.durationText`, published
  label from `subtitle`. `showId` is null here.
- Show episode: same renderer plus the enclosing show's ID and title.
- Episode search: `musicResponsiveListItemRenderer`; title from
  `flexColumns[0]`, show/date from `flexColumns[1]`, video ID from
  `playlistItemData.videoId`, artwork from `thumbnail`.
- Duration strings such as `1 hr 29 min`, `45 min`, and `1:02:03` are parsed;
  unparseable values become null.
- Published label keeps YouTube's relative text (`18h ago`, `Oct 9`); no fake
  timestamps are generated.
- Missing/renamed fields skip the item instead of throwing. Parsers never
  perform network calls.

### Repository

`PodcastRepository` is a `@Singleton` with:

- In-memory caches for home shelves (10 minutes) and show pages (5 minutes).
- `suspend fun home(): List<PodcastShelf>` gated by `podcastsEnabled`.
- `suspend fun show(showId, continuation): PodcastShowPage` gated by the toggle.
- `suspend fun search(query): List<PodcastEpisode>` gated by the toggle.
- `fun isEnabled(): Boolean` and a settings flow so screens can react live.
- Toggle-off cancels registered load jobs and returns empty results without
  throwing.
- Progress APIs delegate to `PodcastProgressStore`.

Caching stays in memory; no episode catalog is persisted. This avoids stale
archives and keeps Room limited to progress.

### Persistence

Add Room table `podcast_progress`:

```kotlin
@Entity(tableName = "podcast_progress", indices = [Index("showId")])
data class PodcastProgressEntity(
    @PrimaryKey val videoId: String,
    val showId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtMillis: Long,
)
```

DAO:

- `get(videoId)`
- `upsert(entity)`
- `observeForShow(showId): Flow<List<PodcastProgressEntity>>`
- `latestForShow(showId): PodcastProgressEntity?`
- `clear()`

`AppDatabase` version 14 -> 15; add `PodcastProgressEntity`, `podcastProgressDao()`,
and `migration14To15` in `DatabaseModule` that creates the table and index.
Add the DAO provider. Include `podcastProgressDao.clear()` in the existing
clear-all-data flow.

`PodcastProgressStore` clamps positions, applies the 10 s / 95% rules, and is
injected into `MusicPlayer` for save/restore.

### Playback changes

In `MusicPlayer`:

- Add `@Serializable enum class MediaType { SONG, PODCAST_EPISODE }`.
- Add `mediaType: MediaType = MediaType.SONG` and `showId: String? = null` to
  `PlayableTrack`; defaults keep old persisted sessions decodable.
- Add `playPodcastQueue(...)` as described above.
- Add podcast guards:
  - `startRadioQueue` and radio continuation.
  - queue enrichment/related-song fetching.
  - crossfade decision.
  - progress save hook.
- Keep `play(track, startRadio = true)` unchanged for music. Podcast UI never
  calls it with the default.

In the service/UI:

- `MusicPlaybackService` skips now-playing and scrobble calls when
  `mediaType != SONG`.
- Lyrics loading in `PlayerHost` returns early for podcast episodes.
- Song stats/related graph/taste writers skip podcast episodes.

### Settings

- `MiscSettings.podcastsEnabled = true`.
- `Keys.PODCASTS_ENABLED`; read with `?: true`; add
  `SettingsPreferences.setPodcastsEnabled`.
- `SettingsViewModel.setPodcastsEnabled`.
- `SettingsScreen` Library tab gets a `Content` section with one toggle card.
- String resources: `settings_podcasts_title`,
  `settings_podcasts_on_subtitle`, `settings_podcasts_off_subtitle`; add the
  English values in `values/strings.xml`.

### UI and navigation

New files under `ui/podcast/`:

- `PodcastsScreen.kt`
- `PodcastsViewModel.kt`
- `PodcastShowScreen.kt`
- `PodcastShowViewModel.kt`

Navigation:

- `Screen.Podcasts` (`podcasts`).
- `Screen.PodcastShow` (`podcast_show/{showId}`) with `createRoute(showId)`.
- `NavGraph` composables; both observe `misc.podcastsEnabled` and pop when it
  becomes false.
- Thread `onOpenPodcasts` and `onOpenPodcastShow` through `MainShell` to
  `FeedScreen`.
- `FeedScreen` renders the Home podcast shelf using `FeedData.podcastShows`.
- `FeedRepository` gets `PodcastRepository` and `SettingsPreferences`; when
  enabled it fetches `home()` in parallel with other feed work and takes the
  first show shelf. Add `podcastShows` (and the shelf title if needed) to
  `FeedData`; include the toggle in the feed cache key.

## Error handling

- All podcast network calls are `runCatching`-guarded at the repository boundary;
  failures return empty/previous cached data and never block music.
- Home/Podcasts screens show a retry action; show screen shows a retry row.
- Region-unavailable or empty landing: friendly empty state, no repeated retries.
- Filter-token drift: dynamic chip discovery with fallback constant, then
  unfiltered page-type filtering.
- Parse drift: skip malformed rows, log once per response under
  `LastWavePodcast`.
- Playback errors use the existing `MusicPlayer` retry/error surfaces.
- Toggle off while a load is in flight: cancel and ignore the result.

## Testing

Unit tests (JUnit + Truth, matching existing test style):

1. `PodcastParserTest`
   - Home fixtures: show shelf + episode shelves.
   - Show fixture: header, episode fields, continuation token.
   - Episode search fixture: title/show/date/video ID.
   - Malformed/missing fields are skipped.
2. `PodcastFilterResolverTest`
   - Extracts `Episodes` params from chip JSON.
   - Falls back to the constant and page-type filtering.
3. `PodcastEpisodeMappingTest`
   - Maps to `PlayableTrack` with `mediaType`, `showId`, artist fallback.
   - Serialization round-trip for `PlayableTrack`/`MediaType`.
4. `PodcastProgressStoreTest`
   - 10 s ignore, 95% completion, position clamping, show latest selection.
5. `PodcastToggleTest`
   - Default true; repository returns empty/no calls when disabled; progress is
     not deleted.

Manual verification:

- Home shelf appears, opens Podcasts and Show screens.
- Show pagination works beyond the first 20 episodes.
- Episode plays without starting music radio; next episode advances.
- Kill/relaunch and resume an episode.
- Turn the toggle off and verify all surfaces disappear, no requests fire, and
  current playback continues.
- Verify Last.fm/lyrics/radio do not run for episodes.

Build commands:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

## Phasing

1. Data/parser + fixture tests + Room progress migration.
2. Home shelf, Podcasts screen, Show screen, navigation, setting.
3. Playback queue/resume + music-behavior guards.
4. Toggle-off behavior, error/empty states, manual verification.

## Risks

- InnerTube podcast browse IDs/filter tokens are unofficial and can change;
  dynamic chip discovery and defensive parsing reduce breakage.
- YouTube podcast catalog availability varies by region/account.
- Full archives can be large; pagination is required and only progress is stored.
- Room schema change; migration must be added and clear-all-data updated.
- Podcast metadata has no stable show link for landing/search episodes; those
  play as single episodes with a generic "Podcast" artist until opened from a
  show page.

## Acceptance criteria

- One new setting, `Podcasts`, on by default; off hides every podcast surface and
  stops new podcast requests without deleting progress or stopping playback.
- Podcast content is sourced from `FEmusic_podcasts`, show pages, and YouTube
  `Episodes` search only; no RSS or local ranking.
- A show's episodes paginate and play in YouTube order with no music radio.
- Episode progress resumes after relaunch and completed episodes are marked.
- Podcast episodes never scrobble, fetch lyrics, affect taste/related data, or
  use crossfade.
- Unit tests and `assembleDebug` pass.
