# Home chips at top + YTM-style card system (native shelves only)

Goal: pin the YTM chip row under the app bar, and render native YTM shelves
with a card system that matches each shelf's real data instead of one square
card for everything.

Protected: everything not listed. Only native shelves + the chip row change.
Chip loading/filtering/selection logic is byte-for-byte unchanged.

## Step 2 table (verified against live guest InnerTube browse FEmusic_home)

Header: `musicCarouselShelfBasicHeaderRenderer` -> `title.runs`, `strapline.runs`
(uppercase eyebrow, e.g. "MUSIC THAT'S HOT AND HAPPENING!"), `subtitle.runs`,
`moreContentButton.buttonRenderer.navigationEndpoint.browseEndpoint`.

| Shelf pattern (YTM)                          | Renderer / structure                                  | Fields available                                   | Card variant        |
|----------------------------------------------|-------------------------------------------------------|----------------------------------------------------|---------------------|
| Songs / albums / playlists (default)         | musicTwoRowItemRenderer, aspectRatio=SQUARE            | title, subtitle runs (artist/type/plays), artwork  | SquareCard          |
| Recommended music videos / video shelf       | musicTwoRowItemRenderer, aspectRatio=RECTANGLE_16_9    | title, channel, views                              | WideCard            |
| Big featured card ("Your daily discover"…)   | musicCardShelfRenderer (title/subtitle/thumb/buttons)  | title, subtitle (caption), thumbnail, onTap,menu   | FeaturedCard        |
| Community playlist (collage + 3 songs)       | musicPlaylistShelfRenderer in contents + header        | playlist title, author/views, songs[] (responsive) | CommunityPlaylistCard|
| List rows (quick-picks style)                | musicResponsiveListItemRenderer                        | flexColumns title/artist, playlistItemData         | SquareCard (square) |
| Unknown / missing renderer or fields         | anything else                                          | —                                                  | SquareCard (default)|

Note: the "collage + 3 songs" community card is a Metrolist-style augmentation
(playlist card + its first tracks), not a single Home renderer. We build it
when a playlist shelf item resolves a native `musicPlaylistShelfRenderer` /
rich playlist item with embedded songs; otherwise the playlist falls back to
SquareCard. aspectRatio lives ON the musicTwoRowItemRenderer item (verified:
`MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE`); `RECTANGLE_16_9` marks
video/wide items.

## Plan (exact files)

1. `data/music/HomeShelf.kt`  — add additive fields: `HomeShelfItem` extras
   (aspectRatio, itemType, caption) + `HomeShelf.strapline/moreBrowseId`;
   new `HomeCommunitySongs`/collage holders. All new fields default null/empty.
2. `data/music/HomeShelfExtras.kt` (NEW) — `parseShelfItemExtras()`: reads
   `aspectRatio`, pageType -> itemType, caption text, community songs. Returns
   a small data holder; additive, no signature changes.
3. `InnerTubeMusicApi.kt` — inside `parseCarouselShelf` loop, call the new
   extras parser and attach results to items; read `moreContentButton`
   browseEndpoint in `parseCarouselShelfHeader`-adjacent additive step.
   No existing signature changes.
4. `ui/feed/cards/` (NEW files):
   - `HomeCardVariant.kt` — enum + pure `selectCardVariant(item)` + tests.
   - `ShelfSquareCard.kt`, `ShelfWideCard.kt`, `ShelfFeaturedCard.kt`,
     `ShelfCommunityPlaylistCard.kt`, `ShelfHeaderV2.kt`.
5. `ui/feed/FeedScreen.kt` — move `HomeChipsRow` into `PinnedChipsBar` under
   the app bar (sticky above the LazyColumn); render native shelves through
   the card system; keep synthetic sections untouched.
6. Chip/community enrichment — when a Playlist shelf item has community
   preview songs / collage, render `ShelfCommunityPlaylistCard`.
7. Tests — `HomeCardVariantTest.kt`, `HomeShelfExtrasTest.kt`.
8. Skeleton loaders shaped like real cards (`ShelfCardSkeletons.kt`);
   responsive widths via `CardSizing.kt`.

## Result

- Chips pinned under app bar; mid-feed chip item removed. Behaviour unchanged.
- Native shelves dispatch per-item through `selectCardVariant()` -> square /
  wide / featured / community; unknown shapes fall back to square.
- Shelf header v2 renders strapline eyebrow + subtitle + more-chevron (only
  when the destination is a playlist/album the app already opens) + Play all.
- Side-by-side APK: `app-cardtest.apk` (applicationId `com.lastwave.app.cardtest`).

## Known limitation

- `musicCardShelfRenderer` / `musicPlaylistShelfRenderer` only appear on
  logged-in Home responses; guest feeds are all `musicTwoRowItemRenderer`.
  Featured/community variants therefore engage mainly on signed-in feeds —
  which is also where YTM shows them.
