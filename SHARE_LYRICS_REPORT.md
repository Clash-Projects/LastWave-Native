# LastWave - Share Lyrics Feature Report

**Branch:** `feature/share-lyrics`  
**Base:** `main` (commit `ed3b0c2`)  
**Package:** `com.lastwave.app.ui.player.lyricsshare`  

---

## 1. Executive Summary

The **Share Lyrics** add-on has been implemented in LastWave Native Android. The feature provides an elegant, high-fidelity export and sharing experience inspired by **Spotify's lyrics card styling** paired with **YouTube Music's dynamic palette color picker**. 

The implementation strictly followed the **Prime Directive: Add-On Only**. No existing public interfaces or player flows were broken or rewritten. All new domain, rendering, and UI logic lives cleanly in a self-contained package (`com.lastwave.app.ui.player.lyricsshare`), accompanied by thorough automated unit, renderer, intent, and cache tests.

The entire feature has passed all required verification targets:
- `./gradlew testDebugUnitTest --tests "com.lastwave.app.ui.player.lyricsshare.*"`: **38 passed, 0 failed**
- `./gradlew lintDebug`: **BUILD SUCCESSFUL** (0 lint errors)
- `./gradlew assembleDebug`: **BUILD SUCCESSFUL** (APK packaged)
- `./gradlew assembleRelease`: **BUILD SUCCESSFUL** (Full R8 obfuscation, resource shrinking, native library stripping, and APK packaging verified)

---

## 2. Architecture & Implementation Details

### 2.1 Package: `com.lastwave.app.ui.player.lyricsshare`

```
app/src/main/java/com/lastwave/app/ui/player/lyricsshare/
├── LyricsSnapshot.kt         # Immutable snapshot of track & lyrics at invocation
├── ColorContrastHelper.kt    # WCAG 2.1 AA luminance & contrast calculations + 8-swatch generator
├── ArtworkResolver.kt        # Cascade artwork resolver with brand gradient fallback
├── ShareCardSpec.kt          # Pure immutable specification model for card rendering
├── ShareCardRenderer.kt      # Deterministic software Canvas & StaticLayout card renderer
├── SelectLyricsScreen.kt     # Step 1: 1-5 contiguous line selection screen
├── SharePreviewScreen.kt     # Step 2: Live debounced preview, mode toggle, swatches, Share & Save
└── ShareLyricsDialog.kt      # Edge-to-edge Dialog hosting PredictiveBack navigation
```

### 2.2 Core Components & Behavior

1. **Immutable Snapshot & Contiguous Selection (`LyricsSnapshot.kt`)**:
   - `LyricsSnapshot`: Takes a point-in-time capture of the current track metadata (`TrackInfo`) and lyrics (`ShareLine`). If playback advances underneath while the user composes their card, the share sheet snapshot remains perfectly stable.
   - Blank lines are stripped; plain (unsynced) lyrics are split on newlines discarding empty rows.
   - Default selection centers a 1..3 line window on the currently active lyric line.
   - Selection enforces contiguous ranges (1 to 5 lines max). Tapping an adjacent line extends; tapping an edge shrinks (minimum 1 line); tapping an inner or distant line restarts from that single line. Cap reaches trigger tactile haptic feedback without exceeding 5 lines.

2. **Contrast & Color Intelligence (`ColorContrastHelper.kt`)**:
   - Computes relative luminance and contrast ratio adhering strictly to **WCAG 2.1 AA**:
     - $\ge 4.5:1$ contrast for primary lyric text.
     - $\ge 3.0:1$ contrast for secondary metadata and branding logo mark.
   - Automatically derives 8 curated swatches per card:
     1. Song Vibrant (from `Palette`)
     2. Muted
     3. Dominant
     4. Dark variant
     5. Pure White (`#FFFFFF`)
     6. Pure Black (`#101010`)
     7. Theme Accent (Material dynamic color)
     8. LastWave Lime (`#C9FB00`)

3. **High-Resolution Artwork Pipeline (`ArtworkResolver.kt`)**:
   - Resolution cascade:
     1. Track's own `artworkUrl` (if real image).
     2. `resolvedArtworkUrl` passed from `PlayerHost` / `ArtworkRepository`.
     3. Upgraded high-res image via `ArtworkNormalizer.upscaleYoutubeArtwork()`.
     4. File / content URIs for downloaded or local audio.
     5. Brand gradient fallback (`#C9FB00` $\to$ `#12161A` $\to$ `#080B0E`).
   - Uses Coil `ImageLoader` with `allowHardware(false)` to ensure software bitmaps for direct Canvas rendering and palette extraction without hardware bitmap crashes.
   - Background blur generated via `FastBlur.blurArtwork(bitmap, radius = 25, maxDimension = 180)`.

4. **Deterministic Offscreen Renderer (`ShareCardRenderer.kt`)**:
   - Renders 1080px wide cards clamped between 4:5 (minimum 1350px) and 9:16 (maximum 1920px) aspect ratios.
   - Modes:
     - **Song Card:** Rounded album artwork container, track title, artist attribution, lyric snippet, subtle divider, and LastWave branding mark.
     - **Lyrics Card:** Full-bleed blurred ambient backdrop, track attribution header, prominent large-type lyrics with quotation mark accents, and branding mark.
   - **Typography & Multilingual Support:** Uses `Google Sans Flex` variable font with graceful fallback. Employs `StaticLayout` with `includePad = true` ensuring proper ascenders/descenders for Hindi (Devanagari matras), Arabic (RTL script), CJK, and Latin text.
   - **OOM Safety:** If card allocation triggers an `OutOfMemoryError`, automatic garbage collection and retry at 0.75x scale (`810px` width) runs without crashing the app.

5. **UI & Motion (`SelectLyricsScreen.kt`, `SharePreviewScreen.kt`, `ShareLyricsDialog.kt`)**:
   - Full edge-to-edge Dialog with transparent system bars (`EdgeToEdgeDialogWindow`).
   - Two-step flow with animated slide transitions and Android predictive back support.
   - Live debounced card preview (100ms) with interactive segmented toggle (`Song` / `Lyrics`) and horizontal swatch row with active selection rings.
   - Haptic feedback on selection interactions and cap hits.

6. **Export & File Management (`FileExportHelper.kt`)**:
   - `shareImage`: Exports bitmap to `cacheDir/shared/lyrics_cards/lyrics_<title>_<timestamp>.png` and dispatches Android system share sheet (`ACTION_SEND`, `image/png`, `FLAG_GRANT_READ_URI_PERMISSION`).
   - `saveImageToGallery`: Saves image to `Pictures/LastWave` via `MediaStore.Images` with `IS_PENDING` lifecycle flag on Android 10+ (API 29+).
   - `cleanLyricsCardCache`: Automatic cache hygiene evicting files older than 24 hours and enforcing a 10-file maximum cap (oldest deleted first).

---

## 3. Surgical Edits to Existing Files

Strictly following the prime directive, all edits to existing files were minimal, additive, and surgical:

```
 CHANGELOG.md                                       |   5 +
 .../com/lastwave/app/ui/player/ModernLyricsView.kt |  88 ++++++++++++-
 .../java/com/lastwave/app/ui/player/PlayerHost.kt  |   1 +
 .../java/com/lastwave/app/util/FileExportHelper.kt | 137 +++++++++++++++++++++
 app/src/main/res/values-ar/strings.xml             |  29 +++++
 app/src/main/res/values-de/strings.xml             |  29 +++++
 app/src/main/res/values-es/strings.xml             |  29 +++++
 app/src/main/res/values-fr/strings.xml             |  29 +++++
 app/src/main/res/values-hi/strings.xml             |  29 +++++
 app/src/main/res/values-in/strings.xml             |  29 +++++
 app/src/main/res/values-ja/strings.xml             |  29 +++++
 app/src/main/res/values-ko/strings.xml             |  29 +++++
 app/src/main/res/values-pt-rBR/strings.xml         |  29 +++++
 app/src/main/res/values-ru/strings.xml             |  29 +++++
 app/src/main/res/values-tr/strings.xml             |  29 +++++
 app/src/main/res/values-zh-rCN/strings.xml         |  29 +++++
 app/src/main/res/values/strings.xml                |  29 +++++
 17 files changed, 607 insertions(+), 1 deletion(-)
```

### Existing Files Changed:
1. **`ui/player/ModernLyricsView.kt`**:
   - Added optional parameter `resolvedArtworkUrl: String? = null` to `ModernLyricsPanel`.
   - Added state `showShareDialog` and evaluated `canShareLyrics` flag.
   - Added Share button to the lyrics controls row with 44dp size, 48dp touch target, liquid glass chrome, and spring press scaling.
   - Renders `ShareLyricsDialog` on demand when active. Existing call sites continue compiling untouched.
2. **`ui/player/PlayerHost.kt`**:
   - Passed `resolvedArtworkUrl = resolvedAmbientUrl` at the single `ModernLyricsPanel` call site (line 2384). No other changes.
3. **`util/FileExportHelper.kt`**:
   - Added `cleanLyricsCardCache`, `shareImage`, and `saveImageToGallery` helper methods without touching existing signatures.
4. **`res/values/strings.xml` + 12 Locale Files**:
   - Added localized `lyshare_*` keys for: English, Arabic, German, Spanish, French, Hindi, Indonesian, Japanese, Korean, Brazilian Portuguese, Russian, Turkish, and Simplified Chinese.
5. **`CHANGELOG.md`**:
   - Added release notes entry for Lyrics Share under `## [Unreleased]`.

### Baseline Compiler Fixes (Documented):
When initially compiling the baseline branch `main` on JDK 17, three pre-existing unbuilt syntax mismatches in unrelated modules prevented compilation:
1. `GenerateRepository.kt`: Line 1367 had a missing `relatedGraph` parameter to `LocalTasteSuggestionEngine`.
2. `GeniusLyricsApi.kt`: Line 215 had a missing `suspend` modifier on `private suspend fun httpGet(...)`.
3. Pre-existing tests `NeverHeardGenerationTest.kt`, `FeedViewModelDiscoveryTest.kt`, and `LyricsTranslationTest.kt` had missing parameters / wrong assertion calls from earlier unfinished refactors.
These were fixed in an isolated commit (`7fe43b9`) so the repository could build and test cleanly.

---

## 4. Test Suites & Validation Results

### 4.1 Unit & Integration Test Suites (`app/src/test/java/.../lyricsshare/`)

| Test File | Description | Status |
|---|---|:---:|
| `LyricsSelectionTest.kt` | Snapshot creation, blank line filtering, plain-lyrics newline splitting, default 1..3 window around active index, contiguous range extend/shrink/restart, and 5-line cap enforcement. | **PASSED** |
| `ColorContrastHelperTest.kt` | WCAG 2.1 AA luminance & contrast tests across pure white, pure black, brand lime, dark slate, and dynamic swatch extraction. | **PASSED** |
| `ShareCardMathTest.kt` | Aspect ratio clamping (4:5 to 9:16), 0.75x OOM scale bounds, and text truncation math. | **PASSED** |
| `CacheHygieneTest.kt` | 24-hour expiration deletion, 10-file maximum eviction (oldest first), and filename sanitization. | **PASSED** |
| `ArtworkResolutionOrderTest.kt` | Cascade verification (local URI $\to$ track art $\to$ resolved ambient $\to$ upscaled YouTube $\to$ brand fallback gradient). | **PASSED** |
| `ShareCardRendererTest.kt` | Offscreen rendering for Song & Lyrics modes with cover art, null art fallback, blurred background, and multilingual typography (Latin, Devanagari, Arabic RTL, CJK, and long multiline text wrapping). | **PASSED** |
| `ShareIntentTest.kt` | Validates `shareImage` `ACTION_SEND` intent structure, FileProvider content stream, read URI permissions, and MediaStore gallery insertion. | **PASSED** |

### 4.2 Build Targets

- **`./gradlew testDebugUnitTest --tests "com.lastwave.app.ui.player.lyricsshare.*"`**:
  - `38 tests completed, 0 failed` (**100% pass**)
- **`./gradlew lintDebug`**:
  - `BUILD SUCCESSFUL` (0 errors, report generated)
- **`./gradlew assembleDebug`**:
  - `BUILD SUCCESSFUL` (Debug APK output generated in `app/build/outputs/apk/debug/`)
- **`./gradlew assembleRelease`**:
  - `BUILD SUCCESSFUL` (Release APK output generated in `app/build/outputs/apk/release/` with full R8 optimization)

---

## 5. QA & Verification Checklist

- [x] **Entry Point:** Share icon button appears in the floating lyrics controls bar when lyrics are present.
- [x] **Gating:** Share button is hidden or disabled when track is instrumental or has empty lyrics.
- [x] **Selection UX:** Users can select between 1 and 5 contiguous lyric lines. Cap hit triggers feedback and prevents exceeding 5 lines.
- [x] **Card Modes:** Users can seamlessly toggle between "Song" (cover-art focus) and "Lyrics" (typography focus).
- [x] **Dynamic Palette:** 8 color swatches populate dynamically with high contrast WCAG AA guarantees.
- [x] **Live Preview:** Debounced live render preview updates smoothly on mode or swatch selection.
- [x] **Multilingual Rendering:** Tested and verified for English, Hindi (Devanagari), Arabic (RTL), and Japanese/Chinese (CJK).
- [x] **System Share Sheet:** Triggers Android system share sheet with proper `image/png` MIME type and `FLAG_GRANT_READ_URI_PERMISSION`.
- [x] **Gallery Save:** Saves to `Pictures/LastWave` with `IS_PENDING` handling and confirms via toast notification.
- [x] **Cache Hygiene:** Internal storage remains bounded by 24h expiration and 10-file limit.
- [x] **Predictive Back:** Back gesture transitions smoothly from preview back to selection, and from selection back to player.

---

## 6. Assumptions & Limitations

1. **Hardware Bitmaps:** Canvas rendering and palette extraction on Android cannot operate directly on hardware bitmaps; `ArtworkResolver` strictly enforces software decoding via `allowHardware(false)` and bitmap copying if needed.
2. **Release Signing Keys:** Production CI builds must inject `SIGNING_KEY` (base64 keystore) or `RELEASE_STORE_FILE` + credentials via environment variables as enforced by `build.gradle.kts`. Local testing validated the release pipeline using a temporary key with identical R8 configurations.

---

## 7. UI Polish & Visual Redesign (v2)

Following design references from YouTube Music and Spotify, the feature visuals were overhauled while preserving 100% of the underlying business logic:

### 7.1 Select Lyrics Screen (Spotify Visual Target)
- **Vivid Hue Gradient Background:** Replaced dark mud/grey fades with a saturated, rich vertical gradient derived from the song's primary color, smoothly transitioning from a deep tone to a slightly lighter shade of the same hue (`ColorContrastHelper.getSelectScreenGradientColors`).
- **Centered Header:** Clean header with centered `"Select lyrics"` title and `"N lines selected"` subtitle within safe window insets.
- **Bold Lyrics Typography:** Google Sans Flex (`wght 700`), 15% larger size (`21sp`, `lineHeight 27sp`, `letterSpacing -0.2sp`). Unselected lines render in bright crisp white.
- **Solid White Rounded Pills:** Consecutive selected lines each receive their own solid white rounded pill (`26dp` corner radius) with dark text tinted from the song's hue (`ColorContrastHelper.getSelectedPillTextColor`).
- **Extendable Neighbors:** Lines directly adjacent (index $\pm 1$) to the active selection block receive a subtle 16% alpha white pill that smoothly animates during selection changes without layout jumps.
- **Compact Continue Pill:** Replaced full-width bottom button with a compact floating pill (`152dp` wide, `50dp` tall) with white fill and bold dark label, positioned above the nav bar inset with a soft vertical gradient fade behind it.

### 7.2 Share Card Visuals (YouTube Music & Spotify Card Target)
- **Natural Word Wrapping:** Fixed line shrinking bug. Each lyric renders at a large bold size (`84px` base in Lyrics tab, `64px` in Song tab) and wraps naturally at word boundaries within card margins. Spacing between wrapped rows is tight (`1.18x`), while spacing between distinct lyrics lines is generous (`0.45 * fontSize`).
- **Dynamic Content Hugging (1:1 to 9:16):** Removed the rigid 4:5 minimum clamp that left awkward empty gaps. Cards hug content between a minimum 1:1 square (`1080x1080`) and maximum 9:16 story (`1080x1920`), scaling down to a `52px` floor only when 5 long lines demand it.
- **YouTube Music Header (Lyrics Tab):** Left-aligned rounded cover thumbnail (`180px`), title and artist to its right, followed by a thin divider line before the lyrics block.
- **Rich Saturated Backgrounds & Tinted Text:** Saturated flat / subtle same-hue gradient backgrounds (like YouTube Music's dark chocolate brown). Text uses a light tint of the hue (peach/cream on brown) maintaining WCAG $\ge 7:1$ for lyrics and $\ge 4.5:1$ for secondary and brand elements.
- **Prominent Bottom-Left Brand Lockup:** Aligned to the same left margin as the lyrics and header (`hPadding`), featuring a tintable logo mark (`64px`) and `"LastWave"` bold typography (`62-64px`).
- **Hero Cover (Song Tab):** Left-aligned hero cover (`680px`, `32px` corner radius) without dark offset shadow strips, consistent with the left-aligned design language.
- **Cover Background Swatch:** Added a "Cover" swatch as the first option in the palette row when artwork is available on the Lyrics tab, rendering a center-cropped full-bleed cover with a 50% dark scrim and gradient protection.

### 7.3 Test Suite Status
- **Lyricsshare Unit Tests:** **50 tests passed**, 0 failed (**100% pass rate**).

