# PR Plan: YouTube Music Native Radio Queue

## Goal
Ensure playback queues are built directly from YouTube Music's native radio endpoint (`playlistId = RDAMVM<videoId>`) and continuously extended via native pagination (`nextRadioContinuationData` and automix chaining) in exact YouTube Music order without local re-ranking, shuffling, or text search fallbacks during normal operation.

## Exact Files and Functions Plan (12 Lines Max)
1. `docs/pr-plan-ytm-radio-queue.md`: Architecture notes and commit plan for PR draft.
2. `app/.../data/music/YtRadioResult.kt`: Model holding `tracks`, `continuationToken`, and `automixPlaylistId`.
3. `app/.../data/music/InnerTubeMusicApi.kt:fetchRadio(videoId)`: Sends `/next` with `RDAMVM<videoId>`, returning `YtRadioResult`.
4. `app/.../data/music/InnerTubeMusicApi.kt:fetchRadioContinuation(token)`: Requests next page with continuation token.
5. `app/.../data/music/InnerTubeMusicApi.kt:fetchRadioByPlaylistId(playlistId)`: Chains automix playlist request when continuation is absent.
6. `app/.../data/music/InnerTubeMusicApi.kt:parseRadioResponse(root)`: Extracts tracks preserving exact YTM sequence.
7. `app/.../data/music/InnerTubeMusicApi.kt:extractRadioContinuationToken(root)`: Extracts token from `nextRadioContinuationData` / `continuationCommand`.
8. `app/.../data/music/InnerTubeMusicApi.kt:extractAutomixPlaylistId(root)`: Extracts `watchPlaylistEndpoint.playlistId` from `automixPreviewVideoRenderer`.
9. `app/.../playback/MusicPlayer.kt:startRadioQueue(seed)`: Populates queue from `fetchRadio` in exact YTM order.
10. `app/.../playback/MusicPlayer.kt:extendRadioQueueIfNeeded(index)`: Continuously paginates via continuation token or automix link.
11. `app/.../playback/MusicPlayer.kt:fetchContextAwareRadioTracks()`: Removed from normal path; retained only as error fallback with warning log.
12. `app/.../data/music/YtRadioParserTest.kt`: Unit tests verifying plain/wrapper renderers, continuation, and automix extraction.
