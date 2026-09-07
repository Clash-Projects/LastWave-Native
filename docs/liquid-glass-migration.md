# Kyant Backdrop migration — source review, 2026-09-07

The application uses `io.github.kyant0:backdrop:2.0.1`. The official `kmp` branch's publication coordinates and Maven Central's release metadata both identify 2.0.1 as the latest stable version on the inspection date. No alternative glass dependency or copied shader implementation was added.

Sources: [official publication configuration](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/build.gradle.kts), [published version metadata](https://repo.maven.apache.org/maven2/io/github/kyant0/backdrop/maven-metadata.xml), [Android POM](https://repo.maven.apache.org/maven2/io/github/kyant0/backdrop-android/2.0.1/backdrop-android-2.0.1.pom).

## Compatibility

| Platform/capability | Rendering |
| --- | --- |
| API 21–30 helper path | Theme-colored translucent background; no Backdrop layer allocation, RuntimeShader, or RenderEffect. The app's existing minimum remains API 29, so this does not add installation support for API 21–28. |
| API 31–32, hardware rendering | Kyant blur, modest saturation, tint, plain highlights, and shadow. No runtime shader or lens. |
| API 33+, including API 36 and newer | Same base effects plus Kyant lens on supported corner-based shapes. Chromatic aberration is explicitly false. There is no upper SDK cutoff. |
| Other shapes on API 33+ | Blur/tint/saturation/plain highlight/shadow; lens is omitted instead of passing an unsupported shape. |
| Software rendering, previews, missing source | Lightweight theme-colored fallback; no graphics effects. |

The existing experimental Liquid Glass preference still controls surface glass. The player's pre-existing artwork blur uses Kyant independently of that preference. Foreground lyrics, labels, icons, and input handling remain outside the sampled background.

The published Backdrop AAR declares `minCompileSdk=37`. Its Compose 1.12.0 dependency declares minimum AGP 9.1.0. Source configuration therefore moves to compile SDK 37, AGP 9.1.1, Gradle distribution 9.3.1, Kotlin 2.4.10 and Compose UI 1.12.0. `minSdk=29`, `targetSdk=35`, Java 17 and native audio source remain unchanged. Hilt 2.59.2 supports AGP 9; KSP 2.3.10 includes the Kotlin 2.4 module-name fix. Existing Kotlin plugin/legacy DSL opt-outs remain explicit. Compiler metadata bypass flags and disabled AAR metadata validation were removed, and forced downgrades of transitive coroutine/serialization dependencies were removed.

Sources: [AGP compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes), [Hilt AGP 9 support](https://github.com/google/dagger/releases/tag/dagger-2.59), [KSP compatibility fix](https://github.com/google/ksp/releases/tag/2.3.10).

## Android 16 crash investigation

No LastWave crash stack trace or device reproduction was available. The original code disabled all Backdrop rendering at API 36, attributed it to platform behavior without evidence, and swallowed every throwable from effect setup. Those catches cannot catch native RenderThread faults.

Upstream [issue 54](https://github.com/Kyant0/AndroidLiquidGlass/issues/54) documents a native crash caused by drawing a backdrop inside its own capture. That is evidence for the failure mechanism, not proof that it caused the user's particular crash. The previous root exposed a layer captured around NavHost to descendants; this architecture was removed so descendants cannot sample that enclosing capture. Root surfaces now sample a separate background sibling. No custom uniform calls remain in application glass code; library lens uniform names were inspected against the published source.

Effect setup rejects unspecified, non-finite, and non-positive sizes before blur/lens setup, clamps refraction dimensions to the surface size, and checks API and shape capability before calling the lens. Theme tint is included in the memoized modifier keys. Kyant/Compose own graphics-layer creation and release through `rememberLayerBackdrop` and modifier attachment/detachment. There are no application-owned global GraphicsLayers or catch-all graphics fallbacks.

These are source-level corrections and safeguards. They do not establish the original crash's exact cause or prove crash-free startup, recomposition, scrolling, navigation, rotation, resizing, animation, or future GPU behavior.

## Capture ownership and surface coverage

- Theme: one background-only sibling layer shared by ordinary cards, headers, controls and sheets. On flat screen backgrounds this source is intentionally flat; foreground content is not recursively captured to manufacture detail.
- Main shell: existing pager capture is consumed by its sibling navigation bar.
- Player host: existing content capture is consumed by its sibling mini-player.
- Full player: artwork and contrast scrims form the source; controls and lyrics remain siblings. Raw artwork uses a separate blur source; the blurred output may be captured by the player source without sampling that player source.
- Artist, album and playlist detail: one content capture per screen, consumed only by sibling header overlays. No capture is allocated per list item.

The shared helper migrates its existing callers: navigation, mini-player, full-player controls, feed/search/settings cards and headers, context-menu and player sheets, and excluded-song cards. Additional previously translucent surfaces migrated: classic/modern lyrics controls; detail headers/back/actions and playback badges; lyrics-animation sheet and controls; feed tag/view/retry buttons; home/friend statistic pills; active download cards. No glass was added to previously opaque dialogs. Text opacity, scrims, loading pulses, progress indicators and animated waveform strokes retain their original roles; they are not backdrop surfaces.

## Changed files

Paths below are relative to the project root.

| Files | Change |
| --- | --- |
| `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts` | Required published dependency/toolchain compatibility. |
| `app/src/main/java/com/lastwave/app/ui/theme/LiquidGlass.kt` | Shared Kyant effects, API guards, fallback, artwork blur; removes painted refraction and catch-all handling. |
| `app/src/main/java/com/lastwave/app/ui/theme/Theme.kt`, `app/src/main/java/com/lastwave/app/ui/navigation/NavGraph.kt` | Background sibling ownership; removes enclosing NavHost capture. |
| `app/src/main/java/com/lastwave/app/ui/common/LiquidGlassCard.kt` | Glass, clipping and tint order. |
| `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt`, `app/src/main/java/com/lastwave/app/ui/player/LyricsView.kt`, `app/src/main/java/com/lastwave/app/ui/player/ModernLyricsView.kt` | Kyant artwork blur and existing translucent lyrics controls. |
| `app/src/main/java/com/lastwave/app/ui/artist/ArtistDetailScreen.kt`, `app/src/main/java/com/lastwave/app/ui/album/AlbumDetailScreen.kt`, `app/src/main/java/com/lastwave/app/ui/playlist/PlaylistDetailScreen.kt` | Shared screen captures and detail glass surfaces. |
| `app/src/main/java/com/lastwave/app/ui/feed/FeedScreen.kt`, `app/src/main/java/com/lastwave/app/ui/home/HomeScreen.kt`, `app/src/main/java/com/lastwave/app/ui/home/FriendProfileScreen.kt` | Remaining translucent buttons and statistic pills. Existing unrelated feed edits were preserved. |
| `app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt`, `app/src/main/java/com/lastwave/app/ui/settings/DownloadsScreen.kt` | Lyrics sheet, sheet controls, active download card. |
| `docs/liquid-glass-migration.md` | Dependency evidence, capture ownership, compatibility and validation limits. |

Only source inspection, published metadata/source reading, edits, and diff review were performed. No Gradle invocation, build, test, lint, dependency refresh, installation, APK generation or runtime testing was performed. Toolchain resolution/compilation, visual fidelity and device behavior remain unverified.

## Screenshot follow-up

The shared modifier now explicitly clips sampled pixels to the glass shape, instead of relying on the inner Material surface's clip. Feed header shuffle/play-all glass now draws on the visible content bounds inside its clickable Surface; this avoids painting a second larger shape over the minimum touch-target area. Touch handling and content padding are retained. Player/navigation presets use less blur/tint and stronger edge refraction while retaining the API guards.

Player shuffle/repeat controls share a state-aware button: animated selected fill and icon color, a selected indicator dot, press-scale feedback, and accessibility state descriptions. Repeat-one keeps its distinct icon. These follow-up changes are source-reviewed only; matching the screenshots' intended appearance still needs device verification.

The repeated double-shell button pattern is now handled by `LiquidGlassSurface`: its transparent outer clickable Surface retains Material touch-target sizing, interaction source and semantics; its inner non-clickable Surface owns both the glass modifier and the visible fill/border. Nineteen call sites use it across feed, classic/modern lyrics, player, playlist and settings, including the two feed header actions from the first follow-up. Press transforms and layout modifiers stay on the outer component. This change adds no backdrop captures and does not alter player actions.
