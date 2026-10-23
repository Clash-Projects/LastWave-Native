# Changes made

This document records the current working-tree changes relative to commit `c309b78` on `main`. The upstream remote is `https://github.com/Clash-Projects/LastWave-Native.git`. The application changes have not yet been committed or submitted as a pull request.

## Playback, notifications, and artwork

| File | Changes | Reasoning |
| --- | --- | --- |
| `app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt` | Stops the previous player immediately when resolving a newly selected queue item. Only takes the same-song restart shortcut when that song actually matches the player and no playback request is still active. | Previously the old audio continued while the new stream resolved; repeatedly tapping a resolving selection could also restart the wrong underlying item. The logical buffering state retains service wake/wifi locks while the replacement loads. |
| `app/src/main/java/com/lastwave/app/playback/MusicPlaybackService.kt` | Observes the recents-removal preference. Stops playback on task removal when enabled, preserving the persisted session. With the option off, treats both playing and buffering as active playback. | Makes stopping from recents optional without changing the default background-playback behavior or intentionally deleting the saved queue. |
| `app/src/main/java/com/lastwave/app/playback/MusicPlaybackService.kt` | Includes duration in notification invalidation; includes speed and queue index in media-session invalidation; periodically republishes the playback clock; publishes zero extrapolation speed while buffering. Records a successful system-state signature only after publishing succeeds. | Allows timestamps to update when duration becomes available, resynchronizes external controls without interaction, avoids advancing the notification clock while buffering, and allows failed publications to retry. |
| `app/src/main/java/com/lastwave/app/playback/MusicPlaybackService.kt` | Caches published metadata separately from playback position. Sends cover bitmaps again only when relevant metadata changes. | Five-second clock corrections should not repeatedly send the same cover bitmap through Binder on the main thread. |
| `app/src/main/java/com/lastwave/app/playback/MusicPlaybackService.kt` | Uses track-owned artwork requests and validates image completion against the currently selected song. Selects artwork ownership before foreground promotion and repository-driven updates. Artwork getters return no image or URL for a different song. | A track can change before the state collector or image loader finishes. Every publication path must show the current song's artwork or a placeholder rather than reuse another song's cover. |
| `app/src/main/java/com/lastwave/app/playback/PlaybackServiceState.kt` — new | Extracts the service-state comparator. Suppresses ordinary playing-position ticks within a five-second bucket while preserving duration, speed, track, buffering, and paused-position changes. | Keeps service work below the player progress ticker's cadence while allowing periodic media-session clock corrections. The comparator can also be tested independently. |
| `app/src/main/java/com/lastwave/app/playback/TrackArtwork.kt` — new | Tracks artwork ownership using track identity and request generations. Clears artwork on selection changes and rejects stale completions, including repeated URLs and skipping away and back. | URL equality alone cannot identify which song or which request owns a returned bitmap. The generic holder supports regression tests without Android bitmap decoding. |

## Settings

| File | Changes | Reasoning |
| --- | --- | --- |
| `app/src/main/java/com/lastwave/app/data/local/SettingsPreferences.kt` | Adds `MiscSettings.stopPlaybackOnTaskRemoved`, its DataStore key, loading, and setter. Defaults to `false`. | Persists the user's choice while preserving existing behavior for users who have not enabled it. |
| `app/src/main/java/com/lastwave/app/ui/settings/SettingsViewModel.kt` | Adds the action that saves the recents-removal preference. | Connects the switch to the existing settings persistence/action handling. |
| `app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt` | Adds the switch as the final row in Audio, below Background Playback when that row is visible. Updates row counts for the crossfade and battery-exemption combinations. | Places the setting where requested and keeps grouped row positions correct when optional settings appear or disappear. |
| `app/src/main/java/com/lastwave/app/ui/settings/SettingsSearchIndex.kt` | Adds a searchable entry with recents, swipe, close, stop, background, and playback keywords. | Makes the new option discoverable through settings search. |
| `app/src/main/res/values/strings.xml` | Adds the switch title and explanatory subtitle. | Keeps visible text in Android string resources. Only the default-language strings were added; translations remain future work. |

## Recommendations

| File | Changes | Reasoning |
| --- | --- | --- |
| `app/src/main/java/com/lastwave/app/data/generate/RecommendationEngine.kt` | Precomputes YouTube feed, liked-song, and recent-song key sets once per generation run. | Replaces repeated full-list scans and track-key normalization during candidate scoring with set lookups. |
| `app/src/main/java/com/lastwave/app/data/generate/RecommendationEngine.kt` | Records explored track seeds and chooses unexplored genre seeds across rounds. Reuses the precomputed feed-key set during seed selection. | Avoids asking the same similar-track and genre sources for the same seeds repeatedly in one run. |
| `app/src/main/java/com/lastwave/app/data/generate/RecommendationEngine.kt` | Counts candidates accepted by the freshness callback when deciding whether to refill. Checks only newly added candidates in subsequent rounds. Skips extra source requests when the initial personal pool is already sufficient. | Raw pool size can overstate how many songs are eligible for selection. Eligibility-aware refilling reduces short results, while avoiding repeated checks and unnecessary requests. The repository's final exclusion filter remains in place. |
| `app/src/main/java/com/lastwave/app/data/generate/RecommendationEngine.kt` | Preserves a primary-genre match when merging duplicate candidates. Keys album diversity counts by normalized artist plus album name. | A later genre match should contribute to scoring; unrelated artists with identically named albums should not share an album cap. |
| `app/src/main/java/com/lastwave/app/data/generate/RecommendationEngine.kt` | Rethrows coroutine cancellation from source-fetch error handlers. | Cancelling generation should stop its work rather than be treated as an ordinary provider miss and continue fetching. |

These are changes to the existing scoring and candidate pipeline, not a replacement recommendation model. They do not establish measured gains in recommendation relevance.

## Scrolling, animations, and gestures

| File | Changes | Reasoning |
| --- | --- | --- |
| `app/src/main/java/com/lastwave/app/ui/player/DraggableSleepTimerOverlay.kt` | Adds `PlayerSleepTimerOverlay`, which collects only distinct remaining-second values inside the overlay. | The timer displays seconds; player progress ticks should not invalidate the surrounding app content. |
| `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt` | Removes collection of the entire high-frequency playback state from the app-wide host and uses the isolated sleep-timer overlay. | Prevents the host and its scrolling content from participating in progress-driven recompositions just to obtain the timer value. |
| `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt` | Reads full-player vertical motion in the graphics layer. Tracks finger movement synchronously, using a stationary gesture modifier before the translated layer. Animates from the released position only after the gesture ends. | Keeps the panel under the finger, including slow drags and pauses, without launching a coroutine for every movement or recomposing the large player screen for every pixel. |
| `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt` | Preserves the released offset during collapse instead of simultaneously springing back toward zero. Uses a derived boolean to pause canvas playback during displacement. | Avoids competing return and exit animations, and limits canvas-related composition changes to the displacement threshold. |
| `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt` | Initializes the queue list at the current song and skips its first animated follow-scroll. | Avoids animating through a potentially long queue while the queue panel is entering. Later track-following and the explicit scroll-to-current button remain available. |
| `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt` | Tracks a reordered row by stable key and absolute viewport top. Calculates its visual translation from the current layout position. Disables placement animation on the dragged/settling row, elevates the outer row above siblings, and waits for each move to be reflected before issuing another. | Manual compensation plus lazy-list placement animation caused the row to jump away from the finger and then return. Stable ownership and one visual coordinate remove the competing offsets and avoid repeated moves against stale indices. |
| `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt` | Adds a release-settling animation based on the released viewport position and the latest slot position; clears stale drag ownership when its song disappears. | Allows the row to settle into its new slot without abruptly resetting its drag translation, including when release closely follows a reorder. |
| `app/src/main/java/com/lastwave/app/ui/player/FluidArtworkBackground.kt` | Removes the explicit 30 fps animation throttle and follows Compose's display frame clock. Draws the intentionally blurred background at one-third linear resolution, scales it to fill, and scales blur sigma accordingly. | Allows high-refresh animation while reducing the background's render-target area to roughly one ninth of full resolution. It does not reduce the foreground cover or text resolution. |
| `app/src/main/java/com/lastwave/app/ui/player/FluidArtworkBackground.kt` | Caches the blur effect by sigma, reuses the shared Coil image loader, and derives fallback visibility from animation thresholds. | Avoids recreating effects/loaders unnecessarily and avoids recomposing fallback visibility on every fade frame. |
| `app/src/main/java/com/lastwave/app/ui/shell/MainShell.kt` | Changes `beyondViewportPageCount` from zero to one. | Retains adjacent pages to reduce first-composition work during homepage/tab swipes. This trades additional retained page content and memory for smoother transitions. |

The high-refresh display request already existed in `MainActivity.kt`; that file was not changed. The user reported improved overall frame rate before the final three queue/gesture fixes. Sustained 120 fps across devices and the latest gesture behavior have not been measured or independently device-tested.

## Side-by-side comparison builds

| File | Changes | Reasoning |
| --- | --- | --- |
| `app/build.gradle.kts` | Adds the default app-label manifest placeholder. Debug builds use application ID suffix `.compare`, version suffix `-compare`, and label `LastWave Compare`. Adds a `comparison` build type inherited from debug, signed with the debug key, non-debuggable, profileable, and using debug dependency fallbacks. Enables the explicit native comparison-build flag for this variant. | A filename change alone does not allow side-by-side installation. A separate application ID provides separate app storage and installation identity. The non-debuggable variant reduces debug overhead when comparing performance and supports profiling. Existing release build types retain their original application identity/signing setup. |
| `app/src/main/AndroidManifest.xml` | Uses `${appLabel}` for the application label. | Allows the comparison label while normal release builds continue to resolve `@string/app_name`. Provider authorities already use `${applicationId}` and needed no change. |
| `app/src/main/cpp/CMakeLists.txt` | Defines `LASTWAVE_COMPARISON_BUILD=1` for the audio target only when the comparison option is enabled. | Passes the variant's explicit application-identity configuration to native code. |
| `app/src/main/cpp/SecretsBridge.cpp` | Accepts `com.lastwave.app.compare` in the explicit comparison build and in debuggable native debug builds. Leaves subsequent certificate verification in place. | The previous native package allowlist accepted only the main application ID, which would reject the separately installed comparison app. Ordinary release builds without the comparison flag keep the original package restriction. |

The comparison build intentionally uses a development signing key. It is not an official release APK, and compatibility with production addon credentials has not been verified.

## Regression test files

| File | Coverage and reasoning |
| --- | --- |
| `app/src/test/java/com/lastwave/app/data/generate/RecommendationEngineTest.kt` — new | Three tests cover avoiding unnecessary refills, refilling based on eligibility with strict exclusions and no duplicate freshness checks, and cancellation propagation. |
| `app/src/test/java/com/lastwave/app/playback/PlaybackServiceStateTest.kt` — new | Four tests cover progress throttling with periodic clock updates, duration becoming available, buffering/speed changes, and paused/playing seeks. |
| `app/src/test/java/com/lastwave/app/playback/TrackArtworkTest.kt` — new | Six tests cover track changes before collector updates, shared image URLs, skipping away and back, different recordings with the same title, progress updates and stopping, and failed replacement artwork. |

## Java/toolchain setup edits

These edits were already present during the later app-fix work after Android Studio setup. They are recorded here separately from the playback/UI changes and have not been removed.

| File | Current change | PR guidance |
| --- | --- | --- |
| `gradle.properties` | Adds `org.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk`. | This is a machine-specific JDK path. Leave this change out of the upstream app-fix PR; keep personal configuration in user-level Gradle settings instead. |
| `settings.gradle.kts` | Adds the Foojay toolchain resolver convention plugin, version `1.0.0`. | This is portable build-setup functionality, but separate from the app fixes. Include only if maintainers want automatic JDK provisioning. |
| `gradle/gradle-daemon-jvm.properties` — new/generated | Pins daemon toolchain version 17 and includes generated download URLs for operating systems/architectures. | Treat daemon provisioning as a separate build-setup decision. The suggested app-fix staging command below leaves it out. |

## Build artifacts and validation

- Debug builds and later `:app:assembleComparison` builds completed successfully. The final gesture-fix APK was built with `./gradlew :app:assembleComparison --offline`.
- Earlier targeted regression runs passed all 13 tests in the three new test classes. An earlier run also passed the three existing `NeverHeardGenerationTest` tests. The entire unit-test suite was not run.
- The latest UI/gesture changes were compiled and packaged; tests/device automation were not run for them because the user requested to perform device testing themselves.
- `apksigner verify` succeeded for the final APK. APK metadata confirmed `com.lastwave.app.compare`, label `LastWave Compare`, and a non-debuggable application.
- `git diff --check` passed during the final build work.
- The latest APK is `builds/LastWave-Compare-Queue-Gestures.apk`. Earlier comparison APKs are also local build artifacts. APKs are already ignored by Git and should not be force-added to the source PR.
- Generated native secret headers, local SDK configuration, credentials, signing keys, build directories, and temporary helper scripts are not source changes for this PR.
- `changes_made.md` — new: this file records the changed files, intent, validation history, and PR preparation notes.

## Preparing the upstream pull request

Create a feature branch before committing. The following staging command includes the application code, build variants, native changes, new tests, and this document, while leaving the separate root-level Java/toolchain setup edits unstaged:

```bash
git switch -c fix/playback-queue-performance
git add app changes_made.md
git diff --cached --stat
git diff --cached --check
```

Review `git diff --cached` before committing. This staging command assumes the working tree still contains only the app changes documented here. If more edits are made later, review and stage them deliberately.

The repository's `CONTRIBUTING.md` asks contributors to run `./gradlew testDebugUnitTest`, push a branch to a fork, and open a PR against `main` with a summary, problem analysis, and testing notes. The latest complete suite has not been run; disclose that and let CI/reviewer testing establish the remaining coverage. No new tests were run while writing this document.

Suggested PR title:

> Fix playback notifications, player gestures, and queue performance

Suggested PR description:

> Adds an optional stop-on-recents-removal setting and fixes stale notification artwork, delayed duration updates, and previous audio continuing during track changes. Reduces progress-driven UI work, improves high-refresh player rendering, and fixes queue entrance, swipe-down dismissal, and drag-reordering behavior. Recommendation generation avoids repeated lookups and refills using eligible candidates. Includes a separately installable, non-debuggable comparison build.
>
> Validation: comparison APK builds and signature checks passed. Thirteen targeted new regression tests and three existing recommendation tests passed earlier in development. The latest UI/gesture changes have build-only validation; device testing and the full unit-test suite remain outstanding. See `changes_made.md` for file-level details and tradeoffs.

The comparison/native-build support and recommendation changes are independent review areas. Maintainers may prefer them in follow-up PRs; the document separates them so that decision is straightforward.
