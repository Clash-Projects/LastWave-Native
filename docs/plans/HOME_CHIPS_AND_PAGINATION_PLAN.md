# Step 1 Plan: Home Chips & Home Pagination (YTM)

1. `InnerTubeMusicApi.kt`: Define `HomeChip(title, params)` and `HomePage(shelves, chips, continuationToken)`.
2. `InnerTubeMusicApi.kt`: `parseHomeChips(root)` parses `sectionListRenderer.header.chipCloudRenderer.chips`.
3. `InnerTubeMusicApi.kt`: `parseHomeContinuationToken(root)` parses `nextContinuationData.continuation`.
4. `InnerTubeMusicApi.kt`: `parseHomeContinuationShelves(root)` parses `continuationContents.sectionListContinuation`.
5. `InnerTubeMusicApi.kt`: `fetchHomePage()`, `fetchHomeContinuation(token)`, `fetchHomeChip(chip)` with auth fallback.
6. `FeedRepository.kt`: Add `homeChips` and `homeContinuationToken` to `FeedData`, populate in `loadFeed()`.
7. `FeedViewModel.kt`: Add state flows for `chips`, `selectedChip`, `pagedShelves`, `isLoadingMore`, `isChipLoading`.
8. `FeedViewModel.kt`: Implement `selectChip(chip)` toggling chip, loading chip shelves, hiding synthetic sections.
9. `FeedViewModel.kt`: Implement `loadMoreHomeShelves()` with in-flight, stale-response, and duplicate-shelf guards.
10. `FeedScreen.kt`: Render `HomeChipsRow` directly above native shelves with liquid-glass highlight.
11. `FeedScreen.kt`: Add loading spinner for chip switch, `ExpressiveInlineLoadingIndicator` footer for pagination.
12. `HomeShelfParserTest.kt` & new tests: Unit tests for chip parsing, token extraction, shelf dedupe, end-of-list.
