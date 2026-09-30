# LastWave Music Widgets Architecture & Complete Source Reference

This document provides a complete, line-by-line, production-ready specification of the entire widget system in **LastWave Native**. It contains the comprehensive architecture, design principles, lifecycle state machines, IPC constraints, and the full source code for every file in the widget module.

If you ever rebase, reset, or regenerate your codebase, this document serves as the single source of truth to recreate the widget system with 100% fidelity.

---

## 1. System Architecture & High-Level Design

LastWave ships with **three distinct home screen widgets**:

1. **Material You Two-Tone Split Card (`TwoToneSplitWidget`)**
   - **Framework**: Modern Jetpack Glance (`GlanceAppWidget`) built on Compose principles.
   - **Visual Archetype**: 50/50 Dual-Zone dynamic tonal surface.
     - **Left Pane (50%)**: Dynamic light accent surface housing:
       - Top Bar: Circular Sound Wave Badge (launches app) and Favorite Heart button (instant toggle).
       - Track Metadata: Track title and artist/album subtitle (tappable to open player).
       - Playback Sound Wave: Dynamic visual wave when playing, straight baseline when paused/stopped.
       - Three-Button Playback Deck: Previous, Play/Pause center pill, Next.
     - **Right Pane (50%)**: Deep complementary container framing album artwork with a smooth `26.dp` squircle.
   - **Color Extraction**: Real-time two-tone palette extraction via AndroidX Palette with separate Day and Night mode color variants, preventing IPC overhead on theme toggle.

2. **Classic Now Playing Widget (`NowPlayingWidgetReceiver` & `WidgetViews`)**
   - **Framework**: Traditional Android `RemoteViews` + `AppWidgetProvider`.
   - **Responsive Breakpoints**:
     - *Compact horizontal* (< 220dp width, e.g., 2x1): Hero art + metadata + primary Play FAB.
     - *Standard horizontal* (>= 220dp width, < 115dp height, e.g., 4x1): Art + metadata + EQ + controls + bottom progress bar.
     - *Square / Tall* (< 250dp width, >= 115dp height, e.g., 2x2, 3x2): Centered large art + title/artist + progress + centered controls.
     - *Expanded* (>= 250dp width, >= 115dp height, e.g., 4x2, 5x2): 88dp hero art + metadata + controls + progress.
   - **Compatibility**: Android 12+ `RemoteViews(sizeMap)` multi-size mapping with runtime dimension fallback for Android 10/11.

3. **Frosted Obsidian Glass Widget (`ObsidianGlassWidgetReceiver` & `ObsidianWidgetViews`)**
   - **Framework**: `RemoteViews` styled with frosted glass surfaces, smoked translucent backdrops, specular crystal borders, and expressive playback pills.

---

## 2. Key Architecture Principles & Solved Problems

### A. Process Death & Background Detection (`isAppInBackground`)
- **The Problem**: When the app is swiped away from Recents or killed by the OS, `SharedPreferences` still retains `isPlaying = true`. If the widget rendered based solely on `snapshot.isPlaying`, it would show a "Pause" icon and active animation on a dead player. Tapping play would unexpectedly cold-boot background playback or fail silently.
- **The Solution**:
  - `WidgetActions.isAppInBackground(context)` verifies active playback service, foreground activity count, and active player sessions.
  - All widget renders compute:
    ```kotlin
    val isPlayingNow = isAppInBackground && snapshot.isPlaying
    ```
  - When the app is not in the background:
    - Widget shows **Play** icon and static idle baseline.
    - Tapping Play, Prev, Next, Heart, or anywhere on the widget **only opens the app** (`openAppPending`), never cold-starting background playback.

### B. 0ms Immediate Glance Toggle
- Remote Glance widgets normally suffer from asynchronous IPC roundtrips when buttons are clicked.
- In `WidgetActionCallbacks.kt`, clicking Play/Pause or Favorite triggers an immediate local state update via `updateAppWidgetState` and `widget.update(context, glanceId)` before delegating to the playback service. This eliminates perceived tap latency (0ms icon flip).

### C. IPC & Binder Memory Optimization
- Android Binder transactions have a strict 1 MB per-process limit. Transmitting raw bitmaps triggers fatal `TransactionTooLargeException` or launcher OOMs.
- **Solution Pipeline**:
  1. `ArtworkTransportSanitizer`: Rescales bitmaps to max 512x512 px and compresses JPEGs under 200 KiB.
  2. Disk Persistence: Artwork is saved locally to `context.filesDir/widget_art_current.jpg`.
  3. `AlbumArtBitmapCache`: In-memory 4 MiB LRU cache avoids disk decodes during rapid recompositions.
  4. Recycled Bitmap Protection: All bitmap usages explicitly verify `!bitmap.isRecycled`.

### D. Unified Synchronous Updates
- `WidgetUpdater.pushState(context, snapshot)` dispatches state changes to both Glance (`TwoToneSplitWidget`) and RemoteViews (`NowPlayingWidgetReceiver` & `ObsidianGlassWidgetReceiver`) simultaneously.

---

## 3. Complete Source Code Reference

### 3.1 Data & State Holding

#### `app/src/main/java/com/lastwave/app/widget/ActiveMediaSessionHolder.kt`
```kotlin
package com.lastwave.app.widget

import android.media.session.MediaController

object ActiveMediaSessionHolder {
    @Volatile var isPlaybackServiceRunning: Boolean = false
    @Volatile var isAppForeground: Boolean = false
    @Volatile var activeActivityCount: Int = 0
    @Volatile var controller: MediaController? = null
    @Volatile var ownToken: android.media.session.MediaSession.Token? = null
    @Volatile var player: com.lastwave.app.playback.MusicPlayer? = null
    @Volatile var likedSongsManager: com.lastwave.app.data.playlist.LikedSongsManager? = null
    @Volatile var currentArtwork: android.graphics.Bitmap? = null

    fun clear(expected: MediaController) {
        if (controller?.sessionToken == expected.sessionToken) controller = null
    }

    fun clearToken(expected: android.media.session.MediaSession.Token?) {
        if (expected != null && ownToken == expected) ownToken = null
    }
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/WidgetData.kt`
```kotlin
package com.lastwave.app.widget

import android.content.Context

data class WidgetSnapshot(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val sourceApp: String = "",
    val sourcePackage: String = "",
    val artPath: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasSession: Boolean = false,
    val progress: Float = 0f,
    val isFavorite: Boolean = false,
    val isShuffle: Boolean = false,
    val repeatMode: Int = 0, // 0: OFF, 1: ALL, 2: ONE
    val rotationAngle: Float = 0f,
    val containerColor: Long = 0xFF0C2922L,
    val primaryAccentColor: Long = 0xFF7EE7C4L,
    val onPrimaryColor: Long = 0xFF07251EL,
    val themeMode: String = "dynamic", // "dynamic", "app", "glass"
    // ── Light-mode surface colors ──────────────────────────────────────────────
    val leftSurfaceColor: Long = 0xFFB6F3C5L,
    val rightSurfaceColor: Long = 0xFF113426L,
    val onLeftColor: Long = 0xFF0C2D1BL,
    val onRightColor: Long = 0xFFB6F3C5L,
    val heartActiveColor: Long = 0xFFE11D48L,
    // ── Dark-mode surface colors ──────────────────────────────────────────────
    val leftSurfaceColorDark: Long = 0xFF1B4D30L,
    val rightSurfaceColorDark: Long = 0xFF0A1F15L,
    val onLeftColorDark: Long = 0xFFB6F3C5L,
    val onRightColorDark: Long = 0xFF7EE7C4L,
) {
    companion object {
        internal const val STORE = "lastwave_widget_now_playing"

        fun read(context: Context): WidgetSnapshot {
            val prefs = runCatching {
                context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            }.getOrNull() ?: return WidgetSnapshot()
            return WidgetSnapshot(
                title = prefs.getString("title", "").orEmpty(),
                artist = prefs.getString("artist", "").orEmpty(),
                album = prefs.getString("album", "").orEmpty(),
                sourceApp = prefs.getString("source_app", "").orEmpty(),
                sourcePackage = prefs.getString("source_package", "").orEmpty(),
                artPath = prefs.getString("art_path", null),
                isPlaying = prefs.getBoolean("is_playing", false),
                isBuffering = prefs.getBoolean("is_buffering", false),
                hasSession = prefs.getBoolean("has_session", false),
                progress = prefs.getFloat("progress", 0f).coerceIn(0f, 1f),
                isFavorite = prefs.getBoolean("is_favorite", false),
                isShuffle = prefs.getBoolean("is_shuffle", false),
                repeatMode = prefs.getInt("repeat_mode", 0),
                rotationAngle = prefs.getFloat("rotation_angle", 0f),
                containerColor = prefs.getLong("container_color", 0xFF0C2922L),
                primaryAccentColor = prefs.getLong("primary_accent_color", 0xFF7EE7C4L),
                onPrimaryColor = prefs.getLong("on_primary_color", 0xFF07251EL),
                themeMode = prefs.getString("theme_mode", "dynamic") ?: "dynamic",
                leftSurfaceColor = prefs.getLong("left_surface_color", 0xFFB6F3C5L),
                rightSurfaceColor = prefs.getLong("right_surface_color", 0xFF113426L),
                onLeftColor = prefs.getLong("on_left_color", 0xFF0C2D1BL),
                onRightColor = prefs.getLong("on_right_color", 0xFFB6F3C5L),
                heartActiveColor = prefs.getLong("heart_active_color", 0xFFE11D48L),
                leftSurfaceColorDark = prefs.getLong("left_surface_color_dark", 0xFF1B4D30L),
                rightSurfaceColorDark = prefs.getLong("right_surface_color_dark", 0xFF0A1F15L),
                onLeftColorDark = prefs.getLong("on_left_color_dark", 0xFFB6F3C5L),
                onRightColorDark = prefs.getLong("on_right_color_dark", 0xFF7EE7C4L),
            )
        }

        fun write(context: Context, value: WidgetSnapshot) {
            runCatching {
                context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                    .putString("title", value.title)
                    .putString("artist", value.artist)
                    .putString("album", value.album)
                    .putString("source_app", value.sourceApp)
                    .putString("source_package", value.sourcePackage)
                    .putString("art_path", value.artPath)
                    .putBoolean("is_playing", value.isPlaying)
                    .putBoolean("is_buffering", value.isBuffering)
                    .putBoolean("has_session", value.hasSession)
                    .putFloat("progress", value.progress.coerceIn(0f, 1f))
                    .putBoolean("is_favorite", value.isFavorite)
                    .putBoolean("is_shuffle", value.isShuffle)
                    .putInt("repeat_mode", value.repeatMode)
                    .putFloat("rotation_angle", value.rotationAngle)
                    .putLong("container_color", value.containerColor)
                    .putLong("primary_accent_color", value.primaryAccentColor)
                    .putLong("on_primary_color", value.onPrimaryColor)
                    .putString("theme_mode", value.themeMode)
                    .putLong("left_surface_color", value.leftSurfaceColor)
                    .putLong("right_surface_color", value.rightSurfaceColor)
                    .putLong("on_left_color", value.onLeftColor)
                    .putLong("on_right_color", value.onRightColor)
                    .putLong("heart_active_color", value.heartActiveColor)
                    .putLong("left_surface_color_dark", value.leftSurfaceColorDark)
                    .putLong("right_surface_color_dark", value.rightSurfaceColorDark)
                    .putLong("on_left_color_dark", value.onLeftColorDark)
                    .putLong("on_right_color_dark", value.onRightColorDark)
                    .apply()
            }
        }

        fun fromPreferences(prefs: androidx.datastore.preferences.core.Preferences, context: Context): WidgetSnapshot {
            val title = prefs[WidgetPrefKeys.TITLE]
            if (title == null) {
                return read(context)
            }
            return WidgetSnapshot(
                title = title,
                artist = prefs[WidgetPrefKeys.ARTIST].orEmpty(),
                album = prefs[WidgetPrefKeys.ALBUM].orEmpty(),
                sourceApp = prefs[WidgetPrefKeys.SOURCE_APP].orEmpty(),
                sourcePackage = prefs[WidgetPrefKeys.SOURCE_PACKAGE].orEmpty(),
                artPath = prefs[WidgetPrefKeys.ART_PATH],
                isPlaying = prefs[WidgetPrefKeys.IS_PLAYING] ?: false,
                isBuffering = prefs[WidgetPrefKeys.IS_BUFFERING] ?: false,
                hasSession = prefs[WidgetPrefKeys.HAS_SESSION] ?: true,
                progress = prefs[WidgetPrefKeys.PROGRESS] ?: 0f,
                isFavorite = prefs[WidgetPrefKeys.IS_FAVORITE] ?: false,
                isShuffle = prefs[WidgetPrefKeys.IS_SHUFFLE] ?: false,
                repeatMode = prefs[WidgetPrefKeys.REPEAT_MODE] ?: 0,
                rotationAngle = prefs[WidgetPrefKeys.ROTATION_ANGLE] ?: 0f,
                containerColor = prefs[WidgetPrefKeys.CONTAINER_COLOR] ?: 0xFF0C2922L,
                primaryAccentColor = prefs[WidgetPrefKeys.PRIMARY_ACCENT_COLOR] ?: 0xFF7EE7C4L,
                onPrimaryColor = prefs[WidgetPrefKeys.ON_PRIMARY_COLOR] ?: 0xFF07251EL,
                themeMode = prefs[WidgetPrefKeys.THEME_MODE] ?: "dynamic",
                leftSurfaceColor = prefs[WidgetPrefKeys.LEFT_SURFACE] ?: 0xFFB6F3C5L,
                rightSurfaceColor = prefs[WidgetPrefKeys.RIGHT_SURFACE] ?: 0xFF113426L,
                onLeftColor = prefs[WidgetPrefKeys.ON_LEFT] ?: 0xFF0C2D1BL,
                onRightColor = prefs[WidgetPrefKeys.ON_RIGHT] ?: 0xFFB6F3C5L,
                heartActiveColor = prefs[WidgetPrefKeys.HEART_ACTIVE] ?: 0xFFE11D48L,
                leftSurfaceColorDark = prefs[WidgetPrefKeys.LEFT_SURFACE_DARK] ?: 0xFF1B4D30L,
                rightSurfaceColorDark = prefs[WidgetPrefKeys.RIGHT_SURFACE_DARK] ?: 0xFF0A1F15L,
                onLeftColorDark = prefs[WidgetPrefKeys.ON_LEFT_DARK] ?: 0xFFB6F3C5L,
                onRightColorDark = prefs[WidgetPrefKeys.ON_RIGHT_DARK] ?: 0xFF7EE7C4L,
            )
        }
    }

    fun writeToPreferences(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[WidgetPrefKeys.TITLE] = title
        prefs[WidgetPrefKeys.ARTIST] = artist
        prefs[WidgetPrefKeys.ALBUM] = album
        prefs[WidgetPrefKeys.SOURCE_APP] = sourceApp
        prefs[WidgetPrefKeys.SOURCE_PACKAGE] = sourcePackage
        if (artPath != null) {
            prefs[WidgetPrefKeys.ART_PATH] = artPath
        } else {
            prefs.remove(WidgetPrefKeys.ART_PATH)
        }
        prefs[WidgetPrefKeys.IS_PLAYING] = isPlaying
        prefs[WidgetPrefKeys.IS_BUFFERING] = isBuffering
        prefs[WidgetPrefKeys.HAS_SESSION] = hasSession
        prefs[WidgetPrefKeys.PROGRESS] = progress
        prefs[WidgetPrefKeys.IS_FAVORITE] = isFavorite
        prefs[WidgetPrefKeys.IS_SHUFFLE] = isShuffle
        prefs[WidgetPrefKeys.REPEAT_MODE] = repeatMode
        prefs[WidgetPrefKeys.ROTATION_ANGLE] = rotationAngle
        prefs[WidgetPrefKeys.CONTAINER_COLOR] = containerColor
        prefs[WidgetPrefKeys.PRIMARY_ACCENT_COLOR] = primaryAccentColor
        prefs[WidgetPrefKeys.ON_PRIMARY_COLOR] = onPrimaryColor
        prefs[WidgetPrefKeys.THEME_MODE] = themeMode
        prefs[WidgetPrefKeys.LEFT_SURFACE] = leftSurfaceColor
        prefs[WidgetPrefKeys.RIGHT_SURFACE] = rightSurfaceColor
        prefs[WidgetPrefKeys.ON_LEFT] = onLeftColor
        prefs[WidgetPrefKeys.ON_RIGHT] = onRightColor
        prefs[WidgetPrefKeys.HEART_ACTIVE] = heartActiveColor
        prefs[WidgetPrefKeys.LEFT_SURFACE_DARK] = leftSurfaceColorDark
        prefs[WidgetPrefKeys.RIGHT_SURFACE_DARK] = rightSurfaceColorDark
        prefs[WidgetPrefKeys.ON_LEFT_DARK] = onLeftColorDark
        prefs[WidgetPrefKeys.ON_RIGHT_DARK] = onRightColorDark
    }
}

object WidgetPrefKeys {
    val TITLE = androidx.datastore.preferences.core.stringPreferencesKey("title")
    val ARTIST = androidx.datastore.preferences.core.stringPreferencesKey("artist")
    val ALBUM = androidx.datastore.preferences.core.stringPreferencesKey("album")
    val SOURCE_APP = androidx.datastore.preferences.core.stringPreferencesKey("source_app")
    val SOURCE_PACKAGE = androidx.datastore.preferences.core.stringPreferencesKey("source_package")
    val ART_PATH = androidx.datastore.preferences.core.stringPreferencesKey("art_path")
    val IS_PLAYING = androidx.datastore.preferences.core.booleanPreferencesKey("is_playing")
    val IS_BUFFERING = androidx.datastore.preferences.core.booleanPreferencesKey("is_buffering")
    val HAS_SESSION = androidx.datastore.preferences.core.booleanPreferencesKey("has_session")
    val PROGRESS = androidx.datastore.preferences.core.floatPreferencesKey("progress")
    val IS_FAVORITE = androidx.datastore.preferences.core.booleanPreferencesKey("is_favorite")
    val IS_SHUFFLE = androidx.datastore.preferences.core.booleanPreferencesKey("is_shuffle")
    val REPEAT_MODE = androidx.datastore.preferences.core.intPreferencesKey("repeat_mode")
    val ROTATION_ANGLE = androidx.datastore.preferences.core.floatPreferencesKey("rotation_angle")
    val CONTAINER_COLOR = androidx.datastore.preferences.core.longPreferencesKey("container_color")
    val PRIMARY_ACCENT_COLOR = androidx.datastore.preferences.core.longPreferencesKey("primary_accent_color")
    val ON_PRIMARY_COLOR = androidx.datastore.preferences.core.longPreferencesKey("on_primary_color")
    val THEME_MODE = androidx.datastore.preferences.core.stringPreferencesKey("theme_mode")
    val LEFT_SURFACE = androidx.datastore.preferences.core.longPreferencesKey("left_surface_color")
    val RIGHT_SURFACE = androidx.datastore.preferences.core.longPreferencesKey("right_surface_color")
    val ON_LEFT = androidx.datastore.preferences.core.longPreferencesKey("on_left_color")
    val ON_RIGHT = androidx.datastore.preferences.core.longPreferencesKey("on_right_color")
    val HEART_ACTIVE = androidx.datastore.preferences.core.longPreferencesKey("heart_active_color")
    val LEFT_SURFACE_DARK = androidx.datastore.preferences.core.longPreferencesKey("left_surface_color_dark")
    val RIGHT_SURFACE_DARK = androidx.datastore.preferences.core.longPreferencesKey("right_surface_color_dark")
    val ON_LEFT_DARK = androidx.datastore.preferences.core.longPreferencesKey("on_left_color_dark")
    val ON_RIGHT_DARK = androidx.datastore.preferences.core.longPreferencesKey("on_right_color_dark")
}

typealias NowPlayingWidgetSnapshot = WidgetSnapshot
```

---

### 3.2 Performance, Cache & Graphics Utilities

#### `app/src/main/java/com/lastwave/app/widget/AlbumArtBitmapCache.kt`
```kotlin
package com.lastwave.app.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import java.io.File

/**
 * High-performance in-memory LRU cache for widget album art bitmaps (4 MiB max).
 * Prevents redundant Binder transactions and expensive BitmapFactory disk decodes
 * during Glance recomposition, enabling instant zero-lag UI updates and smooth animations.
 */
object AlbumArtBitmapCache {
    private const val MAX_CACHE_SIZE_KB = 4 * 1024 // 4 MiB

    private val lru = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun get(key: String): Bitmap? = synchronized(lru) {
        val bitmap = lru.get(key)
        if (bitmap != null && !bitmap.isRecycled) bitmap else null
    }

    fun put(key: String, bitmap: Bitmap) = synchronized(lru) {
        if (!bitmap.isRecycled) {
            lru.put(key, bitmap)
        }
    }

    fun remove(key: String) = synchronized(lru) {
        lru.remove(key)
    }

    fun getOrDecode(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        val modKey = "${path}_${file.lastModified()}"
        synchronized(lru) {
            val cached = lru.get(modKey)
            if (cached != null && !cached.isRecycled) return cached
        }
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        synchronized(lru) {
            lru.put(modKey, decoded)
            lru.put(path, decoded)
        }
        return decoded
    }

    fun clear() = synchronized(lru) {
        lru.evictAll()
    }
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/ArtworkTransportSanitizer.kt`
```kotlin
package com.lastwave.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ensures album artwork is safely scaled and compressed before being passed
 * to Glance AppWidgets.
 *
 * Android Binder IPC has a strict 1 MB total transaction buffer limit.
 * Raw 5-10 MB album covers passed over Binder or large Bitmaps loaded into
 * RemoteViews trigger fatal `TransactionTooLargeException` or OutOfMemory crashes.
 *
 * This sanitizer downscales bitmaps to max 512x512 px and aggressively compresses
 * JPEG output to remain comfortably under 200 KiB.
 */
object ArtworkTransportSanitizer {

    const val MAX_DIMENSION_PX = 512
    const val MAX_BYTES = 200 * 1024 // 200 KiB safe threshold for Binder transactions
    private const val INITIAL_JPEG_QUALITY = 85
    private const val MIN_JPEG_QUALITY = 55
    private const val JPEG_QUALITY_STEP = 6

    fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimensionPx: Int = MAX_DIMENSION_PX): Bitmap {
        val currentMax = max(bitmap.width, bitmap.height)
        if (currentMax <= maxDimensionPx) {
            return bitmap
        }
        val scale = maxDimensionPx.toFloat() / currentMax.toFloat()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return bitmap.scale(targetWidth, targetHeight, filter = true)
    }

    fun compressToByteArray(
        bitmap: Bitmap,
        maxBytes: Int = MAX_BYTES,
    ): ByteArray? {
        var quality = INITIAL_JPEG_QUALITY
        var lastValidBytes: ByteArray? = null

        while (quality >= MIN_JPEG_QUALITY) {
            val output = ByteArrayOutputStream()
            val encoded = runCatching {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }.getOrDefault(false)

            if (encoded) {
                val bytes = output.toByteArray()
                if (bytes.isNotEmpty()) {
                    lastValidBytes = bytes
                    if (bytes.size <= maxBytes) {
                        return bytes
                    }
                }
            }
            quality -= JPEG_QUALITY_STEP
        }
        return lastValidBytes
    }

    fun sanitizeAndSave(
        context: Context,
        source: Bitmap,
        fileName: String = "twotone_widget_art.jpg",
    ): String? {
        return runCatching {
            val scaled = scaleBitmapIfNeeded(source, MAX_DIMENSION_PX)
            val bytes = compressToByteArray(scaled, MAX_BYTES) ?: return null
            val file = File(context.cacheDir, fileName)
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/TwoTonePaletteExtractor.kt`
```kotlin
package com.lastwave.app.widget

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

/**
 * Extracts complementary two-tone color palettes for the TwoToneSplitWidget.
 *
 * Implements the 50/50 Dual-Zone dynamic tonal scheme:
 * - Left pane: Lighter accent/container surface for controls and track metadata.
 * - Right pane: Deeper accent/surface container for the album artwork frame.
 *
 * Exposes separate light and dark variants for each color role so that
 * [TwoToneSplitWidget] can render correctly in both system Day and Night modes
 * without an extra IPC round-trip when the user toggles dark theme.
 */
object TwoTonePaletteExtractor {

    // Default Material You Two-Tone colors
    const val DEFAULT_LEFT_SURFACE: Long = 0xFFB6F3C5L     // Mint green (light)
    const val DEFAULT_RIGHT_SURFACE: Long = 0xFF113426L    // Deep forest green (dark)
    const val DEFAULT_ON_LEFT: Long = 0xFF0C2D1BL          // Dark emerald text on light
    const val DEFAULT_ON_RIGHT: Long = 0xFFB6F3C5L         // Mint icon accent on dark

    // Dark-mode defaults: surfaces swap roles
    const val DEFAULT_LEFT_SURFACE_DARK: Long = 0xFF1B4D30L  // Deeper forest for left in dark
    const val DEFAULT_RIGHT_SURFACE_DARK: Long = 0xFF0A1F15L // Very deep emerald for right in dark
    const val DEFAULT_ON_LEFT_DARK: Long = 0xFFB6F3C5L       // Mint text on dark left
    const val DEFAULT_ON_RIGHT_DARK: Long = 0xFF7EE7C4L      // Lighter mint on deeper dark right

    const val DEFAULT_HEART_ACTIVE: Long = 0xFFE11D48L     // Vibrant rose red

    data class TwoToneColors(
        // Light-mode colors
        val leftSurface: Long = DEFAULT_LEFT_SURFACE,
        val rightSurface: Long = DEFAULT_RIGHT_SURFACE,
        val onLeft: Long = DEFAULT_ON_LEFT,
        val onRight: Long = DEFAULT_ON_RIGHT,
        val heartActive: Long = DEFAULT_HEART_ACTIVE,
        // Dark-mode color variants
        val leftSurfaceDark: Long = DEFAULT_LEFT_SURFACE_DARK,
        val rightSurfaceDark: Long = DEFAULT_RIGHT_SURFACE_DARK,
        val onLeftDark: Long = DEFAULT_ON_LEFT_DARK,
        val onRightDark: Long = DEFAULT_ON_RIGHT_DARK,
    )

    private val paletteCache = androidx.collection.LruCache<String, TwoToneColors>(64)

    @Volatile
    private var lastBitmapHash: Int = 0
    @Volatile
    private var lastExtracted: TwoToneColors? = null

    fun extract(bitmap: Bitmap?, songKey: String = ""): TwoToneColors {
        if (songKey.isNotBlank()) {
            val fromCache = synchronized(paletteCache) { paletteCache.get(songKey) }
            if (fromCache != null) return fromCache
        }
        if (bitmap == null) return TwoToneColors()

        val bitmapHash = System.identityHashCode(bitmap)
        val cachedColors = lastExtracted
        if (bitmapHash != 0 && lastBitmapHash == bitmapHash && cachedColors != null) {
            if (songKey.isNotBlank()) {
                synchronized(paletteCache) { paletteCache.put(songKey, cachedColors) }
            }
            return cachedColors
        }

        return runCatching {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()

            // ── Light-mode surfaces ────────────────────────────────────────────
            val lightColor = palette.getLightVibrantColor(
                palette.getLightMutedColor(
                    palette.getVibrantColor(DEFAULT_LEFT_SURFACE.toInt()),
                ),
            ).toLong() and 0xFFFFFFFFL

            val darkColor = palette.getDarkVibrantColor(
                palette.getDarkMutedColor(
                    palette.getDominantColor(DEFAULT_RIGHT_SURFACE.toInt()),
                ),
            ).toLong() and 0xFFFFFFFFL

            val onLeft = if (isLightColor(lightColor)) 0xFF0C2D1BL else 0xFFFFFFFFL
            val onRight = if (isLightColor(darkColor)) 0xFF113426L else 0xFFB6F3C5L

            // ── Dark-mode surfaces ─────────────────────────────────────────────
            val lightDarkMode = palette.getDarkVibrantColor(
                palette.getMutedColor(
                    DEFAULT_LEFT_SURFACE_DARK.toInt(),
                ),
            ).toLong() and 0xFFFFFFFFL

            val rightDarkMode = palette.getDarkMutedColor(
                palette.getDominantColor(
                    DEFAULT_RIGHT_SURFACE_DARK.toInt(),
                ),
            ).toLong() and 0xFFFFFFFFL

            val onLeftDark = if (isLightColor(lightDarkMode)) 0xFF0A1F15L else 0xFFB6F3C5L
            val onRightDark = if (isLightColor(rightDarkMode)) 0xFF0C2D1BL else 0xFF7EE7C4L

            val result = TwoToneColors(
                leftSurface = lightColor,
                rightSurface = darkColor,
                onLeft = onLeft,
                onRight = onRight,
                heartActive = DEFAULT_HEART_ACTIVE,
                leftSurfaceDark = lightDarkMode,
                rightSurfaceDark = rightDarkMode,
                onLeftDark = onLeftDark,
                onRightDark = onRightDark,
            )
            lastBitmapHash = bitmapHash
            lastExtracted = result
            if (songKey.isNotBlank()) {
                synchronized(paletteCache) { paletteCache.put(songKey, result) }
            }
            result
        }.getOrDefault(TwoToneColors())
    }

    private fun isLightColor(colorLong: Long): Boolean {
        val r = ((colorLong shr 16) and 0xFF).toDouble()
        val g = ((colorLong shr 8) and 0xFF).toDouble()
        val b = (colorLong and 0xFF).toDouble()
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return luminance > 0.5
    }
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/GlanceMaterialIcons.kt`
```kotlin
package com.lastwave.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.glance.ImageProvider
import androidx.glance.LocalContext

/**
 * High-performance Material 3 Compose Vector to Glance ImageProvider bridge.
 *
 * Allows using pure Compose Material 3 icons (e.g. Icons.Rounded.PlayArrow, Icons.Rounded.Pause)
 * directly inside Jetpack Glance widgets with 0ms overhead via an in-memory LRU cache.
 */
object GlanceMaterialIcons {

    private val iconCache = LruCache<String, ImageProvider>(32)

    /**
     * Renders a [VectorPainter] to an Android [Bitmap] at the given size and tint.
     */
    fun renderPainterToBitmap(
        painter: VectorPainter,
        sizePx: Int,
        tint: Color? = null,
        density: Density = Density(1f)
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawScope = CanvasDrawScope()

        drawScope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = androidx.compose.ui.graphics.Canvas(canvas),
            size = Size(sizePx.toFloat(), sizePx.toFloat())
        ) {
            with(painter) {
                draw(
                    size = size,
                    colorFilter = tint?.let { ColorFilter.tint(it) }
                )
            }
        }
        return bitmap
    }

    /**
     * Returns a cached [ImageProvider] for the given [ImageVector] or renders a new one.
     */
    @Composable
    fun rememberVectorProvider(
        image: ImageVector,
        tint: Color = Color.White,
        sizeDp: Dp = 24.dp
    ): ImageProvider {
        val context = LocalContext.current
        val displayDensity = remember(context) { Density(context.resources.displayMetrics.density) }
        val sizePx = remember(sizeDp, displayDensity) {
            with(displayDensity) { sizeDp.roundToPx() }.coerceAtLeast(1)
        }
        val cacheKey = "${image.name}_${tint.toArgb()}_$sizePx"

        val cached = remember(cacheKey) {
            synchronized(iconCache) { iconCache.get(cacheKey) }
        }
        if (cached != null) {
            return cached
        }

        // Bridge Compose runtime LocalDensity so rememberVectorPainter can resolve properly
        var resolvedPainter: VectorPainter? = null
        CompositionLocalProvider(LocalDensity provides displayDensity) {
            resolvedPainter = rememberVectorPainter(image = image)
        }
        val painter = resolvedPainter ?: error("Failed to resolve VectorPainter for ${image.name}")

        return remember(cacheKey, painter) {
            val bitmap = renderPainterToBitmap(
                painter = painter,
                sizePx = sizePx,
                tint = tint,
                density = displayDensity
            )
            val provider = ImageProvider(bitmap)
            synchronized(iconCache) {
                iconCache.put(cacheKey, provider)
            }
            provider
        }
    }
}
```

---

### 3.3 Glance Widget Implementation

#### `app/src/main/java/com/lastwave/app/widget/TwoToneSplitWidget.kt`
```kotlin
package com.lastwave.app.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import com.lastwave.app.MainActivity
import com.lastwave.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Material You Two-Tone Split Card with Heart (WIDGET_REF_03_TWOTONE_SPLIT).
 *
 * Archetype: 50/50 Dual-Zone Dynamic Tonal Surface.
 * - Left Pane: Dynamic Tonal Surface with Note Badge, Heart favorite button, Track Meta,
 *   wavy/straight playback line, and Three-Button Playback Deck (Prev, Play/Pause Center, Next).
 * - Right Pane: Deep Complementary Surface with Album Artwork clipped to RoundedCornerShape(26.dp).
 */
class TwoToneSplitWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val openNowPlayingKey = ActionParameters.Key<Boolean>(WidgetActions.EXTRA_OPEN_NOW_PLAYING)
        val openAppAction = actionStartActivity<MainActivity>(actionParametersOf(openNowPlayingKey to true))

        provideContent {
            val prefs = androidx.glance.currentState<androidx.datastore.preferences.core.Preferences>()
            val snapshot = androidx.compose.runtime.remember(prefs) {
                WidgetSnapshot.fromPreferences(prefs, context)
            }
            val hasContentToShow = snapshot.title.isNotBlank()

            // Resolve artwork bitmap with 0ms in-memory cache priority
            val artBitmap = androidx.compose.runtime.remember(snapshot.artPath, snapshot.title, snapshot.artist) {
                val songKey = "${snapshot.title}|${snapshot.artist}"
                AlbumArtBitmapCache.get(songKey)
                    ?: AlbumArtBitmapCache.get(snapshot.artPath.orEmpty())
                    ?: ActiveMediaSessionHolder.currentArtwork
                    ?: AlbumArtBitmapCache.getOrDecode(snapshot.artPath)
            }

            GlanceTheme {
                TwoToneCardContent(
                    snapshot = snapshot,
                    artBitmap = artBitmap,
                    hasContent = hasContentToShow,
                    openAppAction = openAppAction,
                )
            }
        }
    }

    @Composable
    private fun TwoToneCardContent(
        snapshot: WidgetSnapshot,
        artBitmap: android.graphics.Bitmap?,
        hasContent: Boolean,
        openAppAction: androidx.glance.action.Action,
    ) {
        val context = LocalContext.current
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val leftBgColor = if (isDarkMode) Color(snapshot.leftSurfaceColorDark.toInt()) else Color(snapshot.leftSurfaceColor.toInt())
        val rightBgColor = if (isDarkMode) Color(snapshot.rightSurfaceColorDark.toInt()) else Color(snapshot.rightSurfaceColor.toInt())
        val onLeftTextColor = if (isDarkMode) Color(snapshot.onLeftColorDark.toInt()) else Color(snapshot.onLeftColor.toInt())
        val onRightAccentColor = if (isDarkMode) Color(snapshot.onRightColorDark.toInt()) else Color(snapshot.onRightColor.toInt())

        // Dynamic ColorProviders supporting Day/Night transitions seamlessly
        val leftBgProvider = ColorProvider(leftBgColor)
        val rightBgProvider = ColorProvider(rightBgColor)
        val onLeftTextProvider = ColorProvider(onLeftTextColor)
        val onLeftTextSubProvider = ColorProvider(onLeftTextColor.copy(alpha = 0.72f))
        val onRightAccentProvider = ColorProvider(onRightAccentColor)
        val heartColorProvider = if (snapshot.isFavorite) {
            ColorProvider(Color(snapshot.heartActiveColor.toInt()))
        } else {
            onLeftTextProvider
        }

        val hasSongContent = hasContent && snapshot.title.isNotBlank()
        val isAppInBackground = WidgetActions.isAppInBackground(context)
        val isActivelyPlaying = isAppInBackground && snapshot.isPlaying && !snapshot.isBuffering

        val playPauseAction = if (hasSongContent && isAppInBackground) actionRunCallback<ToggleActionCallback>() else openAppAction
        val prevAction = if (hasSongContent && isAppInBackground) actionRunCallback<PrevActionCallback>() else openAppAction
        val nextAction = if (hasSongContent && isAppInBackground) actionRunCallback<NextActionCallback>() else openAppAction

        // Like button is ONLY disabled when:
        // 1. On default screen with no song loaded (!hasSongContent)
        // 2. When the app is not running in background (!isAppInBackground),
        //    even if cached song details remain in the widget
        // In all other cases (app in background, song loaded, playing OR paused):
        // The like button IS ENABLED and interactive!
        val isLikeButtonEnabled = hasSongContent && isAppInBackground

        val displayTitle = if (hasContent) snapshot.title else "Ready to Play"
        val displaySubtitle = if (hasContent) {
            listOfNotNull(
                snapshot.artist.takeIf { it.isNotBlank() },
                snapshot.album.takeIf { it.isNotBlank() },
            ).joinToString(" • ").ifBlank { "LastWave" }
        } else {
            "Tap to explore music"
        }

        // Outer Card: 360dp x 195dp, RoundedCornerShape(38.dp)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(38.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxSize()) {
                // ==================== LEFT PANE (50%) ====================
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .background(leftBgProvider),
                ) {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // Top Header Row: Animated Sound Wave Badge & Heart Chip
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Circular Sound Wave Badge
                            Box(
                                modifier = GlanceModifier
                                    .size(36.dp)
                                    .background(onLeftTextProvider)
                                    .cornerRadius(18.dp)
                                    .clickable(openAppAction),
                                contentAlignment = Alignment.Center,
                            ) {
                                val soundIcon = if (isActivelyPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = soundIcon,
                                        tint = leftBgColor,
                                        sizeDp = 18.dp,
                                    ),
                                    modifier = GlanceModifier.size(18.dp),
                                    contentDescription = if (isActivelyPlaying) "Playing - Sound Wave" else "Music Note - Open LastWave",
                                )
                            }

                            Spacer(modifier = GlanceModifier.defaultWeight())

                            // Heart Button (placed on top row opposite Note badge)
                            val heartBoxModifier = GlanceModifier
                                .size(36.dp)
                                .background(
                                    when {
                                        isLikeButtonEnabled && snapshot.isFavorite -> Color(0x33E11D48)
                                        isLikeButtonEnabled -> Color(0x1A000000)
                                        else -> Color(0x0A000000)
                                    }
                                )
                                .cornerRadius(18.dp)
                                .clickable(if (isLikeButtonEnabled) actionRunCallback<FavoriteActionCallback>() else openAppAction)

                            Box(
                                modifier = heartBoxModifier,
                                contentAlignment = Alignment.Center,
                            ) {
                                val heartIcon = if (snapshot.isFavorite && (isLikeButtonEnabled || hasContent)) {
                                    Icons.Rounded.Favorite
                                } else {
                                    Icons.Rounded.FavoriteBorder
                                }
                                val heartTint = if (isLikeButtonEnabled) {
                                    if (snapshot.isFavorite) Color(snapshot.heartActiveColor.toInt()) else onLeftTextColor
                                } else {
                                    onLeftTextColor.copy(alpha = 0.32f)
                                }
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = heartIcon,
                                        tint = heartTint,
                                        sizeDp = 20.dp,
                                    ),
                                    modifier = GlanceModifier.size(20.dp),
                                    contentDescription = when {
                                        !isLikeButtonEnabled && !hasSongContent -> "Favorites unavailable - Default screen"
                                        !isLikeButtonEnabled && !isAppInBackground -> "Favorites unavailable - App is not running in background"
                                        snapshot.isFavorite -> "Remove from favorites"
                                        else -> "Add to favorites"
                                    },
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.defaultWeight())

                        // Center: Track Metadata (tappable to open player in app)
                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .clickable(openAppAction),
                        ) {
                            Text(
                                text = displayTitle,
                                style = TextStyle(
                                    color = onLeftTextProvider,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                maxLines = 1,
                            )
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            Text(
                                text = displaySubtitle,
                                style = TextStyle(
                                    color = onLeftTextSubProvider,
                                    fontSize = 12.sp,
                                ),
                                maxLines = 1,
                            )
                        }

                        // Playback Sound Wave: Wavy line when playing, straight baseline when stopped/paused/empty
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .clickable(openAppAction),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            val waveDrawable = if (isActivelyPlaying) {
                                R.drawable.ic_widget_wave_active
                            } else {
                                R.drawable.ic_widget_wave_idle
                            }
                            Image(
                                provider = ImageProvider(waveDrawable),
                                colorFilter = ColorFilter.tint(
                                    if (isActivelyPlaying) onLeftTextProvider else onLeftTextSubProvider
                                ),
                                modifier = GlanceModifier.fillMaxWidth().height(14.dp),
                                contentScale = ContentScale.FillBounds,
                                contentDescription = if (isActivelyPlaying) "Playing Wave" else "Stopped Baseline",
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(4.dp))

                        // Bottom: Three-Button Playback Deck (Prev, Play/Pause Center, Next)
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Previous Button
                            Box(
                                modifier = GlanceModifier
                                    .size(38.dp)
                                    .background(Color(0x1F000000))
                                    .cornerRadius(19.dp)
                                    .clickable(prevAction),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = Icons.Rounded.SkipPrevious,
                                        tint = onLeftTextColor,
                                        sizeDp = 18.dp,
                                    ),
                                    modifier = GlanceModifier.size(18.dp),
                                    contentDescription = "Previous Track",
                                )
                            }

                            Spacer(modifier = GlanceModifier.width(8.dp))

                            // Play/Pause Center Button (launches app to discover music if empty)
                            Box(
                                modifier = GlanceModifier
                                    .size(46.dp)
                                    .background(onLeftTextProvider)
                                    .cornerRadius(23.dp)
                                    .clickable(playPauseAction),
                                contentAlignment = Alignment.Center,
                            ) {
                                val isPlayingNow = isAppInBackground && snapshot.isPlaying
                                val playPauseIcon = if (isPlayingNow) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = playPauseIcon,
                                        tint = leftBgColor,
                                        sizeDp = 22.dp,
                                    ),
                                    modifier = GlanceModifier.size(22.dp),
                                    contentDescription = if (isPlayingNow) "Pause" else "Play",
                                )
                            }

                            Spacer(modifier = GlanceModifier.width(8.dp))

                            // Next Button
                            Box(
                                modifier = GlanceModifier
                                    .size(38.dp)
                                    .background(Color(0x1F000000))
                                    .cornerRadius(19.dp)
                                    .clickable(nextAction),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = Icons.Rounded.SkipNext,
                                        tint = onLeftTextColor,
                                        sizeDp = 18.dp,
                                    ),
                                    modifier = GlanceModifier.size(18.dp),
                                    contentDescription = "Next Track",
                                )
                            }
                        }
                    }
                }

                // ==================== RIGHT PANE (50%) ====================
                // Artwork Container (tappable to open player in app)
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .background(rightBgProvider)
                        .padding(14.dp)
                        .clickable(openAppAction),
                    contentAlignment = Alignment.Center,
                ) {
                    if (artBitmap != null && !artBitmap.isRecycled) {
                        Image(
                            provider = ImageProvider(artBitmap),
                            contentDescription = "Album Cover - Open LastWave",
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .cornerRadius(26.dp),
                        )
                    } else {
                        Image(
                            provider = ImageProvider(R.drawable.widget_art_empty_hero),
                            contentDescription = "LastWave Music - Tap to open player",
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .cornerRadius(26.dp),
                        )
                    }
                }
            }
        }
    }
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/TwoToneSplitWidgetReceiver.kt`
```kotlin
package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receiver for the Material You Two-Tone Split Card Widget.
 * Handles AppWidget lifecycle callbacks and broadcasts for media playback controls.
 */
class TwoToneSplitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TwoToneSplitWidget()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ioScope.launch {
            runCatching {
                glanceAppWidget.updateAll(context)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetActions.ACTION_TOGGLE,
            WidgetActions.ACTION_NEXT,
            WidgetActions.ACTION_PREV,
            WidgetActions.ACTION_FAVORITE,
            WidgetActions.ACTION_SHUFFLE,
            WidgetActions.ACTION_REPEAT -> {
                val action = intent.action ?: return
                val pending = goAsync()
                ioScope.launch {
                    try {
                        when (action) {
                            WidgetActions.ACTION_TOGGLE -> WidgetActions.performToggle(context)
                            WidgetActions.ACTION_NEXT -> WidgetActions.performSkip(context, next = true)
                            WidgetActions.ACTION_PREV -> WidgetActions.performSkip(context, next = false)
                            WidgetActions.ACTION_FAVORITE -> WidgetActions.performFavorite(context)
                            WidgetActions.ACTION_SHUFFLE -> WidgetActions.performShuffle(context)
                            WidgetActions.ACTION_REPEAT -> WidgetActions.performRepeat(context)
                        }
                    } finally {
                        runCatching { pending.finish() }
                    }
                }
                return
            }
            else -> super.onReceive(context, intent)
        }
    }
}
```

---

### 3.4 Action Callbacks & Dispatchers

#### `app/src/main/java/com/lastwave/app/widget/WidgetActionCallbacks.kt`
```kotlin
package com.lastwave.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class ToggleActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!WidgetActions.isAppInBackground(context)) {
            runCatching { WidgetActions.openAppPending(context).send() }
            return
        }

        // Immediate 0ms local toggle in Glance state so the icon flips in the current frame!
        runCatching {
            androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { prefs ->
                val isPlaying = prefs[WidgetPrefKeys.IS_PLAYING] ?: false
                val newPlaying = !isPlaying
                prefs[WidgetPrefKeys.IS_PLAYING] = newPlaying
                if (newPlaying) {
                    // Start in buffering mode so line remains straight until playback confirms
                    prefs[WidgetPrefKeys.IS_BUFFERING] = true
                } else {
                    prefs[WidgetPrefKeys.IS_BUFFERING] = false
                }
            }
            TwoToneSplitWidget().update(context, glanceId)
        }
        WidgetActions.performToggle(context, glanceId)
    }
}

class NextActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!WidgetActions.isAppInBackground(context)) {
            runCatching { WidgetActions.openAppPending(context).send() }
            return
        }
        WidgetActions.performSkip(context, next = true, glanceId = glanceId)
    }
}

class PrevActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!WidgetActions.isAppInBackground(context)) {
            runCatching { WidgetActions.openAppPending(context).send() }
            return
        }
        WidgetActions.performSkip(context, next = false, glanceId = glanceId)
    }
}

class FavoriteActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = runCatching {
            androidx.glance.appwidget.state.getAppWidgetState(
                context,
                androidx.glance.state.PreferencesGlanceStateDefinition,
                glanceId,
            )
        }.getOrNull()
        val snapshot = if (prefs != null) {
            WidgetSnapshot.fromPreferences(prefs, context)
        } else {
            WidgetSnapshot.read(context)
        }

        // Guard: Favorite button should NOT work when nothing is playing, empty state, or app not active
        if (!WidgetActions.isFavoriteActionAvailable(context, snapshot)) {
            runCatching { WidgetActions.openAppPending(context).send() }
            return
        }

        // Determine ground truth of current favorite status to avoid stale toggle flip
        val liveLiked = ActiveMediaSessionHolder.likedSongsManager
        val liveTrack = ActiveMediaSessionHolder.player?.state?.value?.current
        val trackName = liveTrack?.title ?: snapshot.title
        val trackArtist = liveTrack?.artist ?: snapshot.artist
        val trackKey = if (trackName.isNotBlank()) {
            com.lastwave.app.data.generate.GeneratedTrack(name = trackName, artist = trackArtist).key
        } else null

        val currentIsFav = if (liveLiked != null && trackKey != null) {
            trackKey in liveLiked.likedTrackKeys.value
        } else {
            snapshot.isFavorite
        }
        val targetFav = !currentIsFav

        // Immediate 0ms local toggle in Glance state so the heart flips in the current frame!
        runCatching {
            androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { p ->
                p[WidgetPrefKeys.IS_FAVORITE] = targetFav
            }
            TwoToneSplitWidget().update(context, glanceId)
        }
        WidgetActions.performFavorite(context, glanceId)
    }
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/WidgetActions.kt`
```kotlin
package com.lastwave.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import androidx.glance.GlanceId
import com.lastwave.app.MainActivity
import com.lastwave.app.playback.MusicPlaybackService

object WidgetActions {
    const val ACTION_TOGGLE = "com.lastwave.app.widget.action.TOGGLE"
    const val ACTION_NEXT = "com.lastwave.app.widget.action.NEXT"
    const val ACTION_PREV = "com.lastwave.app.widget.action.PREV"
    const val ACTION_FAVORITE = "com.lastwave.app.widget.action.FAVORITE"
    const val ACTION_SHUFFLE = "com.lastwave.app.widget.action.SHUFFLE"
    const val ACTION_REPEAT = "com.lastwave.app.widget.action.REPEAT"

    const val EXTRA_OPEN_NOW_PLAYING = "open_now_playing"

    private const val REQ_TOGGLE = 101
    private const val REQ_NEXT = 102
    private const val REQ_PREV = 103
    private const val REQ_OPEN_APP = 104
    private const val REQ_FAVORITE = 105
    private const val REQ_SHUFFLE = 106
    private const val REQ_REPEAT = 107
    private const val REQ_OPEN_ACCESS = 108

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun actionPending(context: Context, receiver: Class<*>, action: String, reqCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reqCode,
            Intent(context, receiver).setAction(action),
            flags(),
        )

    fun togglePending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_TOGGLE, REQ_TOGGLE)

    fun nextPending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_NEXT, REQ_NEXT)

    fun prevPending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_PREV, REQ_PREV)

    fun favoritePending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_FAVORITE, REQ_FAVORITE)

    fun shufflePending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_SHUFFLE, REQ_SHUFFLE)

    fun repeatPending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_REPEAT, REQ_REPEAT)

    fun openNowPlayingPending(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_NOW_PLAYING, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            flags(),
        )

    fun openAppPending(context: Context): PendingIntent =
        openNowPlayingPending(context)

    fun openAccessPending(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN_ACCESS,
            Intent(Settings.ACTION_NOTIFICATION_LISTERATE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags(),
        )

    internal fun resolveController(context: Context): MediaController? {
        val held = ActiveMediaSessionHolder.controller
        held?.let {
            val state = runCatching { it.playbackState?.state }.getOrNull()
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) return it
        }
        return held ?: ActiveMediaSessionHolder.ownToken?.let { token ->
            runCatching { MediaController(context, token) }.getOrNull()
        }
    }

    suspend fun performToggle(context: Context, glanceId: GlanceId? = null) {
        if (!isAppInBackground(context)) {
            runCatching { openAppPending(context).send() }
            return
        }

        val livePlayer = ActiveMediaSessionHolder.player
        val current = WidgetSnapshot.read(context)
        val wasPlaying = livePlayer?.state?.value?.isPlaying ?: current.isPlaying
        val newPlaying = !wasPlaying

        if (current.title.isNotBlank()) {
            WidgetUpdater.setPlaybackState(context, isPlaying = newPlaying, isBuffering = newPlaying)
        }

        if (livePlayer != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                livePlayer.togglePlayPause()
            }
        } else {
            val controller = resolveController(context)
            if (controller != null) {
                if (wasPlaying) controller.transportControls.pause() else controller.transportControls.play()
            } else {
                sendServiceAction(context, ACTION_TOGGLE)
            }
        }
    }

    suspend fun performSkip(context: Context, next: Boolean, glanceId: GlanceId? = null) {
        if (!isAppInBackground(context)) {
            runCatching { openAppPending(context).send() }
            return
        }

        val livePlayer = ActiveMediaSessionHolder.player
        if (livePlayer != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (next) livePlayer.next() else livePlayer.previous()
            }
        } else {
            val controller = resolveController(context)
            if (controller != null) {
                if (next) controller.transportControls.skipToNext() else controller.transportControls.skipToPrevious()
            } else {
                sendServiceAction(context, if (next) ACTION_NEXT else ACTION_PREV)
            }
        }
    }

    fun isAppInBackground(context: Context): Boolean {
        if (ActiveMediaSessionHolder.isPlaybackServiceRunning) return true
        if (ActiveMediaSessionHolder.isAppForeground) return true
        if (ActiveMediaSessionHolder.activeActivityCount > 0) return true
        if (ActiveMediaSessionHolder.player != null) return true
        if (ActiveMediaSessionHolder.controller != null) return true
        if (ActiveMediaSessionHolder.ownToken != null) return true
        if (resolveController(context) != null) return true
        return false
    }

    fun isFavoriteActionAvailable(context: Context, snapshot: WidgetSnapshot = WidgetSnapshot.read(context)): Boolean {
        // 1. Default screen: no song loaded to like
        if (snapshot.title.isBlank()) {
            return false
        }

        // 2. App must be running in background to register the like
        return isAppInBackground(context)
    }

    suspend fun performFavorite(context: Context, glanceId: GlanceId? = null) {
        val current = WidgetSnapshot.read(context)
        if (!isFavoriteActionAvailable(context, current)) {
            runCatching { openAppPending(context).send() }
            return
        }

        val liveLiked = ActiveMediaSessionHolder.likedSongsManager
        val liveTrack = ActiveMediaSessionHolder.player?.state?.value?.current
        val trackToLike = if (liveTrack != null) {
            com.lastwave.app.data.generate.GeneratedTrack(
                name = liveTrack.title,
                artist = liveTrack.artist,
                album = liveTrack.album,
                artworkUrl = liveTrack.artworkUrl,
            )
        } else if (current.title.isNotBlank()) {
            com.lastwave.app.data.generate.GeneratedTrack(
                name = current.title,
                artist = current.artist,
                album = current.album,
                artworkUrl = current.artPath,
            )
        } else null

        if (liveLiked != null && trackToLike != null) {
            val loved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                liveLiked.toggle(trackToLike)
            }
            WidgetUpdater.setFavorite(context, loved)
        } else {
            sendServiceAction(context, ACTION_FAVORITE)
        }
    }

    suspend fun performShuffle(context: Context, glanceId: GlanceId? = null) {
        sendServiceAction(context, ACTION_SHUFFLE)
    }

    suspend fun performRepeat(context: Context, glanceId: GlanceId? = null) {
        sendServiceAction(context, ACTION_REPEAT)
    }

    private const val SERVICE_ACTION_PREVIOUS = "com.lastwave.app.playback.PREVIOUS"
    private const val SERVICE_ACTION_TOGGLE = "com.lastwave.app.playback.TOGGLE"
    private const val SERVICE_ACTION_NEXT = "com.lastwave.app.playback.NEXT"
    private const val SERVICE_ACTION_FAVORITE = "com.lastwave.app.playback.FAVORITE"
    private const val SERVICE_ACTION_SHUFFLE = "com.lastwave.app.playback.SHUFFLE"
    private const val SERVICE_ACTION_REPEAT = "com.lastwave.app.playback.REPEAT"

    fun sendServiceAction(context: Context, action: String) {
        val serviceAction = when (action) {
            ACTION_TOGGLE -> SERVICE_ACTION_TOGGLE
            ACTION_NEXT -> SERVICE_ACTION_NEXT
            ACTION_PREV -> SERVICE_ACTION_PREVIOUS
            ACTION_FAVORITE -> SERVICE_ACTION_FAVORITE
            ACTION_SHUFFLE -> SERVICE_ACTION_SHUFFLE
            ACTION_REPEAT -> SERVICE_ACTION_REPEAT
            else -> action
        }
        runCatching {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                this.action = serviceAction
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startService(intent)
                } catch (_: IllegalStateException) {
                    context.startForegroundService(intent)
                }
            } else {
                context.startService(intent)
            }
        }
    }
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/WidgetUpdater.kt`
```kotlin
package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.glance.appwidget.updateAll
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "WidgetUpdater"

private const val FORCED_DEBOUNCE_MS = 250L
private const val NORMAL_DEBOUNCE_MS = 300L
private const val FOLLOW_UP_DELAY_MS = 250L

object WidgetUpdater {

    private val updaterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var debouncedUpdateJob: Job? = null

    @Volatile
    private var followUpUpdateJob: Job? = null

    @Volatile
    private var lastPublishedSnapshot: WidgetSnapshot? = null

    @Volatile
    private var lastRawBitmapHash: Int = 0

    @Volatile
    private var lastSanitizedArtPath: String? = null

    private val publishMutex = Mutex()

    fun startWaveAnimation(context: Context) = Unit
    fun stopWaveAnimation() = Unit

    fun requestUpdate(context: Context, updateCall: suspend () -> Unit) {
        val app = context.applicationContext
        debouncedUpdateJob?.cancel()
        debouncedUpdateJob = updaterScope.launch {
            delay(NORMAL_DEBOUNCE_MS)
            updateCall()
        }
    }

    fun requestForcedUpdate(context: Context, updateCall: suspend () -> Unit) {
        val app = context.applicationContext
        debouncedUpdateJob?.cancel()
        debouncedUpdateJob = updaterScope.launch {
            delay(FORCED_DEBOUNCE_MS)
            updateCall()
        }
    }

    fun requestWithFollowUp(
        context: Context,
        immediateCall: suspend () -> Unit,
        followUpCall: suspend () -> Unit,
    ) {
        val app = context.applicationContext
        debouncedUpdateJob?.cancel()
        followUpUpdateJob?.cancel()

        debouncedUpdateJob = updaterScope.launch {
            delay(FORCED_DEBOUNCE_MS)
            immediateCall()
        }

        followUpUpdateJob = updaterScope.launch {
            delay(FORCED_DEBOUNCE_MS + FOLLOW_UP_DELAY_MS)
            followUpCall()
        }
    }

    suspend fun publish(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        sourceApp: String,
        sourcePackage: String,
        art: Bitmap?,
        isPlaying: Boolean,
        isBuffering: Boolean = false,
        isFavorite: Boolean = false,
        isShuffle: Boolean = false,
        repeatMode: Int = 0,
        containerColor: Long = TwoTonePaletteExtractor.DEFAULT_RIGHT_SURFACE,
        primaryAccentColor: Long = TwoTonePaletteExtractor.DEFAULT_LEFT_SURFACE,
        onPrimaryColor: Long = TwoTonePaletteExtractor.DEFAULT_ON_LEFT,
        themeMode: String = "dynamic",
    ) {
        publishInternal(
            context, title, artist, album, sourceApp, sourcePackage,
            art, isPlaying, isBuffering, isFavorite, isShuffle, repeatMode, themeMode,
        )
    }

    suspend fun setPlaying(context: Context, isPlaying: Boolean) = publishMutex.withLock {
        val current = WidgetSnapshot.read(context)
        if (current.title.isBlank()) return@withLock

        val updated = current.copy(
            isPlaying = isPlaying,
            isBuffering = if (isPlaying) current.isBuffering else false,
        )
        WidgetSnapshot.write(context, updated)
        pushState(context, updated)
    }

    suspend fun setPlaybackState(context: Context, isPlaying: Boolean, isBuffering: Boolean) = publishMutex.withLock {
        val current = WidgetSnapshot.read(context)
        if (current.title.isBlank()) return@withLock
        if (current.isPlaying == isPlaying && current.isBuffering == isBuffering) return@withLock

        val updated = current.copy(isPlaying = isPlaying, isBuffering = isBuffering)
        WidgetSnapshot.write(context, updated)
        pushState(context, updated)
    }

    suspend fun setFavorite(context: Context, isFavorite: Boolean) = publishMutex.withLock {
        val current = WidgetSnapshot.read(context)
        if (current.title.isBlank()) return@withLock
        if (current.isFavorite == isFavorite) return@withLock

        val updated = current.copy(isFavorite = isFavorite)
        WidgetSnapshot.write(context, updated)
        pushState(context, updated)
    }

    suspend fun clear(context: Context) = publishMutex.withLock {
        lastPublishedSnapshot = null
        lastRawBitmapHash = 0
        lastSanitizedArtPath = null
        AlbumArtBitmapCache.clear()

        context.filesDir.listFiles { _, name -> name.startsWith("widget_art_") }
            ?.forEach { runCatching { it.delete() } }

        val current = WidgetSnapshot.read(context)
        val cleared = current.copy(
            title = "",
            artist = "",
            album = "",
            artPath = null,
            isPlaying = false,
            hasSession = false,
            progress = 0f,
        )
        WidgetSnapshot.write(context, cleared)
        pushState(context, cleared)
    }

    suspend fun refreshTheme(context: Context) {
        val current = WidgetSnapshot.read(context)
        val last = lastPublishedSnapshot

        val colorsChanged = last == null ||
            last.leftSurfaceColor != current.leftSurfaceColor ||
            last.rightSurfaceColor != current.rightSurfaceColor ||
            last.onLeftColor != current.onLeftColor ||
            last.onRightColor != current.onRightColor ||
            last.heartActiveColor != current.heartActiveColor

        if (colorsChanged) {
            lastPublishedSnapshot = null
            pushAll(context)
        }
    }

    fun shouldUpdateWidget(old: WidgetSnapshot?, new: WidgetSnapshot): Boolean {
        if (old == null) return true
        if (old.title != new.title) return true
        if (old.artist != new.artist) return true
        if (old.album != new.album) return true
        if (old.isPlaying != new.isPlaying) return true
        if (old.isBuffering != new.isBuffering) return true
        if (old.isFavorite != new.isFavorite) return true
        if (old.isShuffle != new.isShuffle) return true
        if (old.repeatMode != new.repeatMode) return true
        if (old.artPath != new.artPath) return true
        if (old.leftSurfaceColor != new.leftSurfaceColor) return true
        if (old.rightSurfaceColor != new.rightSurfaceColor) return true
        if (old.onLeftColor != new.onLeftColor) return true
        if (old.onRightColor != new.onRightColor) return true
        if (old.heartActiveColor != new.heartActiveColor) return true
        if (old.leftSurfaceColorDark != new.leftSurfaceColorDark) return true
        if (old.rightSurfaceColorDark != new.rightSurfaceColorDark) return true
        if (old.onLeftColorDark != new.onLeftColorDark) return true
        if (old.onRightColorDark != new.onRightColorDark) return true
        return false
    }

    private suspend fun publishInternal(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        sourceApp: String,
        sourcePackage: String,
        art: Bitmap?,
        isPlaying: Boolean,
        isBuffering: Boolean,
        isFavorite: Boolean,
        isShuffle: Boolean,
        repeatMode: Int,
        themeMode: String,
    ) = publishMutex.withLock {
        val songKey = "${title}|${artist}"

        val artPath: String? = if (art != null && !art.isRecycled) {
            AlbumArtBitmapCache.put(songKey, art)
            ActiveMediaSessionHolder.currentArtwork = art
            getOrWriteSanitizedArt(context, art, songKey)
        } else {
            val cached = AlbumArtBitmapCache.get(songKey)
            if (cached != null) {
                getOrWriteSanitizedArt(context, cached, songKey)
            } else {
                null
            }
        }

        val effectiveBitmap = art ?: AlbumArtBitmapCache.get(songKey)
        val twoTone = TwoTonePaletteExtractor.extract(effectiveBitmap, songKey)

        val newSnapshot = WidgetSnapshot(
            title = title,
            artist = artist,
            album = album.orEmpty(),
            sourceApp = sourceApp,
            sourcePackage = sourcePackage,
            artPath = artPath,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            hasSession = true,
            progress = 0f,
            isFavorite = isFavorite,
            isShuffle = isShuffle,
            repeatMode = repeatMode,
            rotationAngle = 0f,
            containerColor = twoTone.rightSurface,
            primaryAccentColor = twoTone.leftSurface,
            onPrimaryColor = twoTone.onLeft,
            themeMode = themeMode,
            leftSurfaceColor = twoTone.leftSurface,
            rightSurfaceColor = twoTone.rightSurface,
            onLeftColor = twoTone.onLeft,
            onRightColor = twoTone.onRight,
            heartActiveColor = twoTone.heartActive,
            leftSurfaceColorDark = twoTone.leftSurfaceDark,
            rightSurfaceColorDark = twoTone.rightSurfaceDark,
            onLeftColorDark = twoTone.onLeftDark,
            onRightColorDark = twoTone.onRightDark,
        )

        if (shouldUpdateWidget(lastPublishedSnapshot, newSnapshot)) {
            lastPublishedSnapshot = newSnapshot
            WidgetSnapshot.write(context, newSnapshot)
            pushState(context, newSnapshot)
        }
    }

    suspend fun pushState(context: Context, snapshot: WidgetSnapshot) {
        pushStateToGlance(context, snapshot)
        updateRemoteViews(context)
    }

    private fun updateRemoteViews(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetReceiver::class.java))
            for (appWidgetId in ids) {
                val views = WidgetViews.build(context, appWidgetId)
                runCatching { manager.updateAppWidget(appWidgetId, views) }
            }
            val obsidianIds = manager.getAppWidgetIds(ComponentName(context, ObsidianGlassWidgetReceiver::class.java))
            for (appWidgetId in obsidianIds) {
                val views = ObsidianWidgetViews.build(context, appWidgetId)
                runCatching { manager.updateAppWidget(appWidgetId, views) }
            }
        }.onFailure { Log.w(TAG, "updateRemoteViews failed", it) }
    }

    suspend fun pushStateToGlance(context: Context, snapshot: WidgetSnapshot): Boolean =
        runCatching {
            val glanceManager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
            val glanceIds = glanceManager.getGlanceIds(TwoToneSplitWidget::class.java)
            if (glanceIds.isNotEmpty()) {
                glanceIds.forEach { glanceId ->
                    androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { prefs ->
                        snapshot.writeToPreferences(prefs)
                    }
                    TwoToneSplitWidget().update(context, glanceId)
                }
            } else {
                TwoToneSplitWidget().updateAll(context)
            }
            true
        }.onFailure { Log.w(TAG, "pushStateToGlance failed", it) }.getOrDefault(false)

    suspend fun pushAll(context: Context): Boolean =
        runCatching {
            val current = WidgetSnapshot.read(context)
            pushState(context, current)
            true
        }.onFailure { Log.w(TAG, "widget push failed", it) }.getOrDefault(false)

    suspend fun sync(context: Context) {
        pushAll(context)
    }

    @Synchronized
    private fun getOrWriteSanitizedArt(context: Context, bitmap: Bitmap, songKey: String = ""): String? = runCatching {
        val bitmapHash = System.identityHashCode(bitmap)
        val cachedPath = lastSanitizedArtPath
        if (bitmapHash != 0 && lastRawBitmapHash == bitmapHash && cachedPath != null) {
            val file = File(cachedPath)
            if (file.exists()) return cachedPath
        }

        val finalFile = File(context.filesDir, "widget_art_current.jpg")
        if (songKey.isNotBlank() && AlbumArtBitmapCache.get(songKey) != null && finalFile.exists()) {
            return finalFile.absolutePath
        }

        val scaled = ArtworkTransportSanitizer.scaleBitmapIfNeeded(bitmap, ArtworkTransportSanitizer.MAX_DIMENSION_PX)
        val bytes = ArtworkTransportSanitizer.compressToByteArray(scaled, ArtworkTransportSanitizer.MAX_BYTES)
            ?: return null

        val tempFile = File(context.filesDir, "widget_art_current.tmp")
        tempFile.writeBytes(bytes)

        if (finalFile.exists()) runCatching { finalFile.delete() }
        tempFile.renameTo(finalFile)

        val path = finalFile.absolutePath
        val modKey = "${path}_${finalFile.lastModified()}"
        AlbumArtBitmapCache.put(modKey, scaled)
        AlbumArtBitmapCache.put(path, scaled)
        if (songKey.isNotBlank()) {
            AlbumArtBitmapCache.put(songKey, scaled)
        }

        lastRawBitmapHash = bitmapHash
        lastSanitizedArtPath = path

        context.filesDir.listFiles { _, name ->
            name.startsWith("widget_art_") && name != "widget_art_current.jpg" && name != "widget_art_current.tmp"
        }?.forEach { runCatching { it.delete() } }

        path
    }.onFailure { Log.w(TAG, "failed to cache widget art", it) }.getOrNull()
}
```

---

### 3.5 RemoteViews Widgets Implementation

#### `app/src/main/java/com/lastwave/app/widget/WidgetViews.kt`
```kotlin
package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lastwave.app.R
import java.io.File

/**
 * Multi-size responsive RemoteViews factory. One rule: this NEVER throws.
 * Every decode / lookup is guarded and falls back to placeholders, so
 * onUpdate always pushes valid content.
 */
internal object WidgetViews {

    private const val PROGRESS_MAX = 1000

    private val eqFrames = intArrayOf(
        R.drawable.widget_eq_frame_0,
        R.drawable.widget_eq_frame_1,
        R.drawable.widget_eq_frame_2,
    )

    internal data class Resolved(
        val snapshot: WidgetSnapshot,
        val hasAccess: Boolean,
        val usableSession: Boolean,
    )

    internal fun resolve(context: Context): Resolved {
        val snapshot = WidgetSnapshot.read(context)
        val hasAccess = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)
        val usable = snapshot.hasSession &&
            (hasAccess || snapshot.sourcePackage == context.packageName)
        return Resolved(snapshot, hasAccess, usable)
    }

    /** Reads the launcher's measured width for this exact widget id. */
    private fun minWidthDp(context: Context, appWidgetId: Int): Int = runCatching {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    }.getOrDefault(0)

    /** Reads the launcher's measured height for this exact widget id. */
    private fun minHeightDp(context: Context, appWidgetId: Int): Int = runCatching {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
    }.getOrDefault(0)

    fun build(
        context: Context,
        appWidgetId: Int,
        eqFrame: Int? = null,
        progressOverride: Float? = null,
    ): RemoteViews {
        val resolved = resolve(context)
        val artBitmap = resolveArtBitmap(resolved.snapshot.artPath)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizeMap = mapOf(
                SizeF(120f, 50f) to buildLayout(context, R.layout.widget_now_playing_compact, resolved, appWidgetId, eqFrame, progressOverride, artBitmap),
                SizeF(220f, 50f) to buildLayout(context, R.layout.widget_now_playing, resolved, appWidgetId, eqFrame, progressOverride, artBitmap),
                SizeF(120f, 115f) to buildLayout(context, R.layout.widget_now_playing_square, resolved, appWidgetId, eqFrame, progressOverride, artBitmap),
                SizeF(250f, 115f) to buildLayout(context, R.layout.widget_now_playing_expanded, resolved, appWidgetId, eqFrame, progressOverride, artBitmap),
            )
            return RemoteViews(sizeMap)
        }

        // On Android 10/11 fallback using launcher options
        val width = minWidthDp(context, appWidgetId)
        val height = minHeightDp(context, appWidgetId)
        val layoutId = when {
            height >= 115 && width < 250 -> R.layout.widget_now_playing_square
            height >= 115 && width >= 250 -> R.layout.widget_now_playing_expanded
            width in 1 until 220 -> R.layout.widget_now_playing_compact
            else -> R.layout.widget_now_playing
        }
        return buildLayout(context, layoutId, resolved, appWidgetId, eqFrame, progressOverride, artBitmap)
    }

    private fun buildLayout(
        context: Context,
        layoutId: Int,
        resolved: Resolved,
        appWidgetId: Int,
        eqFrame: Int?,
        progressOverride: Float?,
        artBitmap: Bitmap?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        bind(
            context = context,
            views = views,
            resolved = resolved,
            appWidgetId = appWidgetId,
            eqFrame = eqFrame,
            progressOverride = progressOverride,
            artBitmap = artBitmap,
            isCompact = (layoutId == R.layout.widget_now_playing_compact),
        )
        return views
    }

    private fun bind(
        context: Context,
        views: RemoteViews,
        resolved: Resolved,
        appWidgetId: Int,
        eqFrame: Int?,
        progressOverride: Float?,
        artBitmap: Bitmap?,
        isCompact: Boolean,
    ) {
        val snapshot = resolved.snapshot
        if (!resolved.usableSession) {
            views.setViewVisibility(R.id.widget_empty_group, View.VISIBLE)
            views.setViewVisibility(R.id.widget_content_group, View.GONE)
            views.setImageViewResource(R.id.widget_empty_icon, R.drawable.widget_art_placeholder)
            if (resolved.hasAccess) {
                views.setTextViewText(R.id.widget_empty_title, context.getString(R.string.widget_name))
                views.setTextViewText(R.id.widget_empty_sub, "Start a song in any media app")
                views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openAppPending(context))
            } else {
                views.setTextViewText(R.id.widget_empty_title, "Allow music access")
                views.setTextViewText(R.id.widget_empty_sub, "Tap to detect every media app")
                views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openAccessPending(context))
            }
            return
        }

        views.setViewVisibility(R.id.widget_empty_group, View.GONE)
        views.setViewVisibility(R.id.widget_content_group, View.VISIBLE)

        if (isCompact) {
            views.setViewVisibility(R.id.widget_prev, View.GONE)
            views.setViewVisibility(R.id.widget_next, View.GONE)
            views.setViewVisibility(R.id.widget_eq_group, View.GONE)
            views.setViewVisibility(R.id.widget_progress_row, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_prev, View.VISIBLE)
            views.setViewVisibility(R.id.widget_next, View.VISIBLE)
            views.setViewVisibility(R.id.widget_eq_group, View.VISIBLE)
            views.setViewVisibility(R.id.widget_progress_row, View.VISIBLE)
        }

        views.setTextViewText(
            R.id.widget_title,
            snapshot.title.ifBlank { "Unknown track" },
        )
        views.setTextViewText(
            R.id.widget_subtitle,
            snapshot.artist.ifBlank { "Unknown artist" },
        )
        val isAppInBackground = WidgetActions.isAppInBackground(context)
        val playing = isAppInBackground && snapshot.isPlaying
        views.setTextViewText(
            R.id.widget_state,
            if (playing) "Pause" else "Play",
        )
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        // Animated EQ while playing; frozen first frame while paused.
        runCatching {
            val frame = if (playing) eqFrames[(eqFrame ?: 0).mod(eqFrames.size)]
            else eqFrames[0]
            views.setImageViewResource(R.id.widget_eq_icon, frame)
        }

        // Live progress bar
        runCatching {
            val fraction = (progressOverride ?: snapshot.progress).coerceIn(0f, 1f)
            views.setProgressBar(R.id.widget_progress, PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
        }

        // Prev/next vectors: tint to widget_icon_tint for crisp contrast on glass surfaces
        runCatching {
            val tint = ContextCompat.getColor(context, R.color.widget_icon_tint)
            views.setInt(R.id.widget_prev, "setColorFilter", tint)
            views.setInt(R.id.widget_next, "setColorFilter", tint)
        }

        // Artwork: pre-decoded squircle bitmap or fallback
        if (artBitmap != null && !artBitmap.isRecycled) {
            views.setImageViewBitmap(R.id.widget_art, artBitmap)
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
        }

        val playPausePending = if (isAppInBackground) {
            WidgetActions.togglePending(context, NowPlayingWidgetReceiver::class.java)
        } else {
            WidgetActions.openAppPending(context)
        }
        val prevPending = if (isAppInBackground) {
            WidgetActions.prevPending(context, NowPlayingWidgetReceiver::class.java)
        } else {
            WidgetActions.openAppPending(context)
        }
        val nextPending = if (isAppInBackground) {
            WidgetActions.nextPending(context, NowPlayingWidgetReceiver::class.java)
        } else {
            WidgetActions.openAppPending(context)
        }

        views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openAppPending(context))
        views.setOnClickPendingIntent(R.id.widget_play_pause, playPausePending)
        views.setOnClickPendingIntent(R.id.widget_play_pause_container, playPausePending)
        views.setOnClickPendingIntent(R.id.widget_prev, prevPending)
        views.setOnClickPendingIntent(R.id.widget_prev_container, prevPending)
        views.setOnClickPendingIntent(R.id.widget_next, nextPending)
        views.setOnClickPendingIntent(R.id.widget_next_container, nextPending)
    }

    /**
     * Pre-decodes and rounds artwork once per push to minimize memory and IPC payload.
     */
    private fun resolveArtBitmap(path: String?): Bitmap? = runCatching {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        BitmapFactory.decodeFile(file.absolutePath)?.let { decoded ->
            val art = roundedCorners(decoded, 0.22f)
            if (art !== decoded) runCatching { decoded.recycle() }
            art
        }
    }.getOrNull()

    /**
     * Softens square album art into a rounded squircle. Pure bitmap math —
     * safe to run in any process, including the widget bind path.
     */
    private fun roundedCorners(src: Bitmap, radiusFraction: Float): Bitmap = runCatching {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = minOf(w, h) * radiusFraction
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, 0f, 0f, paint)
        paint.xfermode = null
        out
    }.getOrNull() ?: src
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/NowPlayingWidgetReceiver.kt`
```kotlin
package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one now-playing widget: classic AppWidgetProvider + a single adaptive
 * RemoteViews layout.
 */
class NowPlayingWidgetReceiver : AppWidgetProvider() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (appWidgetId in ids) {
            runCatching {
                manager.updateAppWidget(appWidgetId, WidgetViews.build(context, appWidgetId))
            }
        }
        ioScope.launch {
            runCatching {
                val snapshot = WidgetSnapshot.read(context)
                if (snapshot.hasSession && snapshot.isPlaying && WidgetActions.isAppInBackground(context)) {
                    WidgetUpdater.startWaveAnimation(context.applicationContext)
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        runCatching {
            manager.updateAppWidget(appWidgetId, WidgetViews.build(context, appWidgetId))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetActions.ACTION_TOGGLE,
            WidgetActions.ACTION_NEXT,
            WidgetActions.ACTION_PREV,
            -> {
                val action = intent.action ?: return
                val pending = goAsync()
                ioScope.launch {
                    try {
                        when (action) {
                            WidgetActions.ACTION_TOGGLE -> WidgetActions.performToggle(context)
                            WidgetActions.ACTION_NEXT -> WidgetActions.performSkip(context, next = true)
                            WidgetActions.ACTION_PREV -> WidgetActions.performSkip(context, next = false)
                        }
                    } finally {
                        runCatching { pending.finish() }
                    }
                }
                return
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ioScope.launch { runCatching { WidgetUpdater.sync(context) } }
    }
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/ObsidianWidgetViews.kt`
```kotlin
package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationManagerCompat
import com.lastwave.app.R
import java.io.File

/**
 * RemoteViews factory for the Frosted Obsidian Glass widget.
 */
internal object ObsidianWidgetViews {

    private const val PROGRESS_MAX = 1000

    private val eqFrames = intArrayOf(
        R.drawable.widget_eq_frame_0,
        R.drawable.widget_eq_frame_1,
        R.drawable.widget_eq_frame_2,
    )

    private fun minWidthDp(context: Context, appWidgetId: Int): Int = runCatching {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    }.getOrDefault(0)

    private fun minHeightDp(context: Context, appWidgetId: Int): Int = runCatching {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
    }.getOrDefault(0)

    fun build(
        context: Context,
        appWidgetId: Int,
        eqFrame: Int? = null,
        progressOverride: Float? = null,
    ): RemoteViews {
        val resolved = WidgetViews.resolve(context)
        val artBitmap = resolveArtBitmap(resolved.snapshot.artPath)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizeMap = mapOf(
                SizeF(120f, 50f) to buildLayout(context, R.layout.widget_obsidian_compact, resolved, appWidgetId, eqFrame, progressOverride, artBitmap),
                SizeF(220f, 50f) to buildLayout(context, R.layout.widget_obsidian_horizontal, resolved, appWidgetId, eqFrame, progressOverride, artBitmap),
                SizeF(120f, 115f) to buildLayout(context, R.layout.widget_obsidian_square, resolved, appWidgetId, eqFrame, progressOverride, artBitmap),
                SizeF(250f, 115f) to buildLayout(context, R.layout.widget_obsidian_expanded, resolved, appWidgetId, eqFrame, progressOverride, artBitmap),
            )
            return RemoteViews(sizeMap)
        }

        val width = minWidthDp(context, appWidgetId)
        val height = minHeightDp(context, appWidgetId)
        val layoutId = when {
            height >= 115 && width < 250 -> R.layout.widget_obsidian_square
            height >= 115 && width >= 250 -> R.layout.widget_obsidian_expanded
            width in 1 until 220 -> R.layout.widget_obsidian_compact
            else -> R.layout.widget_obsidian_horizontal
        }
        return buildLayout(context, layoutId, resolved, appWidgetId, eqFrame, progressOverride, artBitmap)
    }

    private fun buildLayout(
        context: Context,
        layoutId: Int,
        resolved: WidgetViews.Resolved,
        appWidgetId: Int,
        eqFrame: Int?,
        progressOverride: Float?,
        artBitmap: Bitmap?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        bind(
            context = context,
            views = views,
            resolved = resolved,
            appWidgetId = appWidgetId,
            eqFrame = eqFrame,
            progressOverride = progressOverride,
            artBitmap = artBitmap,
            isCompact = (layoutId == R.layout.widget_obsidian_compact),
        )
        return views
    }

    private fun bind(
        context: Context,
        views: RemoteViews,
        resolved: WidgetViews.Resolved,
        appWidgetId: Int,
        eqFrame: Int?,
        progressOverride: Float?,
        artBitmap: Bitmap?,
        isCompact: Boolean,
    ) {
        val snapshot = resolved.snapshot
        if (!resolved.usableSession) {
            views.setViewVisibility(R.id.widget_empty_group, View.VISIBLE)
            views.setViewVisibility(R.id.widget_content_group, View.GONE)
            views.setImageViewResource(R.id.widget_empty_icon, R.drawable.widget_art_placeholder)
            if (resolved.hasAccess) {
                views.setTextViewText(R.id.widget_empty_title, context.getString(R.string.widget_obsidian_name))
                views.setTextViewText(R.id.widget_empty_sub, "Start a song in any media app")
                views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openAppPending(context))
            } else {
                views.setTextViewText(R.id.widget_empty_title, "Allow music access")
                views.setTextViewText(R.id.widget_empty_sub, "Tap to detect every media app")
                views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openAccessPending(context))
            }
            return
        }

        views.setViewVisibility(R.id.widget_empty_group, View.GONE)
        views.setViewVisibility(R.id.widget_content_group, View.VISIBLE)

        if (isCompact) {
            views.setViewVisibility(R.id.widget_prev, View.GONE)
            views.setViewVisibility(R.id.widget_next, View.GONE)
            views.setViewVisibility(R.id.widget_eq_group, View.GONE)
            views.setViewVisibility(R.id.widget_progress_row, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_prev, View.VISIBLE)
            views.setViewVisibility(R.id.widget_next, View.VISIBLE)
            views.setViewVisibility(R.id.widget_eq_group, View.VISIBLE)
            views.setViewVisibility(R.id.widget_progress_row, View.VISIBLE)
        }

        views.setTextViewText(
            R.id.widget_title,
            snapshot.title.ifBlank { "Unknown track" },
        )
        views.setTextViewText(
            R.id.widget_subtitle,
            snapshot.artist.ifBlank { "Unknown artist" },
        )
        val isAppInBackground = WidgetActions.isAppInBackground(context)
        val playing = isAppInBackground && snapshot.isPlaying
        views.setTextViewText(
            R.id.widget_state,
            if (playing) "PLAYING" else "PAUSED",
        )
        
        // Update new expressive play/pause pill
        views.setImageViewResource(
            R.id.widget_play_pause_icon,
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setTextViewText(
            R.id.widget_play_pause_text,
            if (playing) "Pause" else "Play",
        )

        // Animated EQ while playing
        runCatching {
            val frame = if (playing) eqFrames[(eqFrame ?: 0).mod(eqFrames.size)]
            else eqFrames[0]
            views.setImageViewResource(R.id.widget_eq_icon, frame)
        }

        // Live progress fraction
        runCatching {
            val fraction = (progressOverride ?: snapshot.progress).coerceIn(0f, 1f)
            views.setProgressBar(R.id.widget_progress, PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
        }

        // Frosted buttons: crisp white icons
        runCatching {
            views.setInt(R.id.widget_prev, "setColorFilter", Color.WHITE)
            views.setInt(R.id.widget_next, "setColorFilter", Color.WHITE)
        }

        // Pre-decoded squircle artwork
        if (artBitmap != null && !artBitmap.isRecycled) {
            views.setImageViewBitmap(R.id.widget_art, artBitmap)
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
        }

        val playPausePending = if (isAppInBackground) {
            WidgetActions.togglePending(context, ObsidianGlassWidgetReceiver::class.java)
        } else {
            WidgetActions.openAppPending(context)
        }
        val prevPending = if (isAppInBackground) {
            WidgetActions.prevPending(context, ObsidianGlassWidgetReceiver::class.java)
        } else {
            WidgetActions.openAppPending(context)
        }
        val nextPending = if (isAppInBackground) {
            WidgetActions.nextPending(context, ObsidianGlassWidgetReceiver::class.java)
        } else {
            WidgetActions.openAppPending(context)
        }

        views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openAppPending(context))
        views.setOnClickPendingIntent(R.id.widget_play_pause, playPausePending)
        views.setOnClickPendingIntent(R.id.widget_prev, prevPending)
        views.setOnClickPendingIntent(R.id.widget_next, nextPending)
    }

    private fun resolveArtBitmap(path: String?): Bitmap? = runCatching {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        BitmapFactory.decodeFile(file.absolutePath)?.let { decoded ->
            val art = roundedCorners(decoded, 0.22f)
            if (art !== decoded) runCatching { decoded.recycle() }
            art
        }
    }.getOrNull()

    private fun roundedCorners(src: Bitmap, radiusFraction: Float): Bitmap = runCatching {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = minOf(w, h) * radiusFraction
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, 0f, 0f, paint)
        paint.xfermode = null
        out
    }.getOrNull() ?: src
}
```

---

#### `app/src/main/java/com/lastwave/app/widget/ObsidianGlassWidgetReceiver.kt`
```kotlin
package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AppWidgetProvider for the Frosted Obsidian Glass widget.
 */
class ObsidianGlassWidgetReceiver : AppWidgetProvider() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (appWidgetId in ids) {
            runCatching {
                manager.updateAppWidget(appWidgetId, ObsidianWidgetViews.build(context, appWidgetId))
            }
        }
        ioScope.launch {
            runCatching {
                val snapshot = WidgetSnapshot.read(context)
                if (snapshot.hasSession && snapshot.isPlaying && WidgetActions.isAppInBackground(context)) {
                    WidgetUpdater.startWaveAnimation(context.applicationContext)
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        runCatching {
            manager.updateAppWidget(appWidgetId, ObsidianWidgetViews.build(context, appWidgetId))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetActions.ACTION_TOGGLE,
            WidgetActions.ACTION_NEXT,
            WidgetActions.ACTION_PREV,
            -> {
                val action = intent.action ?: return
                val pending = goAsync()
                ioScope.launch {
                    try {
                        when (action) {
                            WidgetActions.ACTION_TOGGLE -> WidgetActions.performToggle(context)
                            WidgetActions.ACTION_NEXT -> WidgetActions.performSkip(context, next = true)
                            WidgetActions.ACTION_PREV -> WidgetActions.performSkip(context, next = false)
                        }
                    } finally {
                        runCatching { pending.finish() }
                    }
                }
                return
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ioScope.launch { runCatching { WidgetUpdater.sync(context) } }
    }
}
```

---

### 3.6 AppWidget Provider XML Configurations

#### `app/src/main/res/xml/twotone_split_widget_info.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="280dp"
    android:minHeight="140dp"
    android:minResizeWidth="180dp"
    android:minResizeHeight="110dp"
    android:maxResizeWidth="600dp"
    android:maxResizeHeight="400dp"
    android:targetCellWidth="4"
    android:targetCellHeight="2"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:initialLayout="@layout/widget_twotone_split_preview"
    android:previewLayout="@layout/widget_twotone_split_preview"
    android:previewImage="@mipmap/ic_launcher"
    android:description="@string/widget_twotone_split_description"
    android:updatePeriodMillis="0" />
```

---

#### `app/src/main/res/xml/now_playing_widget_info.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="220dp"
    android:minHeight="68dp"
    android:minResizeWidth="140dp"
    android:minResizeHeight="56dp"
    android:maxResizeWidth="600dp"
    android:maxResizeHeight="400dp"
    android:targetCellWidth="4"
    android:targetCellHeight="1"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:initialLayout="@layout/widget_now_playing"
    android:previewLayout="@layout/widget_now_playing"
    android:previewImage="@mipmap/ic_launcher"
    android:description="@string/widget_description"
    android:updatePeriodMillis="0" />
```

---

#### `app/src/main/res/xml/obsidian_glass_widget_info.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="220dp"
    android:minHeight="68dp"
    android:minResizeWidth="140dp"
    android:minResizeHeight="56dp"
    android:maxResizeWidth="600dp"
    android:maxResizeHeight="400dp"
    android:targetCellWidth="4"
    android:targetCellHeight="1"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:initialLayout="@layout/widget_obsidian_horizontal"
    android:previewLayout="@layout/widget_obsidian_horizontal"
    android:previewImage="@mipmap/ic_launcher"
    android:description="@string/widget_obsidian_description"
    android:updatePeriodMillis="0" />
```

---

### 3.7 Manifest Configuration (`AndroidManifest.xml`)

Place inside the `<application>` tag of `app/src/main/AndroidManifest.xml`:

```xml
        <!-- Classic Now Playing Widget (RemoteViews) -->
        <receiver
            android:name=".widget.NowPlayingWidgetReceiver"
            android:exported="true"
            android:label="@string/widget_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/now_playing_widget_info" />
        </receiver>

        <!-- Frosted Obsidian Glass Widget (RemoteViews) -->
        <receiver
            android:name=".widget.ObsidianGlassWidgetReceiver"
            android:exported="true"
            android:label="@string/widget_obsidian_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/obsidian_glass_widget_info" />
        </receiver>

        <!-- Material You Two-Tone Split Card Widget (Jetpack Glance) -->
        <receiver
            android:name=".widget.TwoToneSplitWidgetReceiver"
            android:exported="true"
            android:label="@string/widget_twotone_split_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
                <action android:name="com.lastwave.app.widget.action.TOGGLE" />
                <action android:name="com.lastwave.app.widget.action.NEXT" />
                <action android:name="com.lastwave.app.widget.action.PREV" />
                <action android:name="com.lastwave.app.widget.action.FAVORITE" />
                <action android:name="com.lastwave.app.widget.action.SHUFFLE" />
                <action android:name="com.lastwave.app.widget.action.REPEAT" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/twotone_split_widget_info" />
        </receiver>
```

---

### 3.8 Service & Application Hooks

#### 1. `LastWaveApplication.kt` Foreground / Activity Tracking
```kotlin
registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        com.lastwave.app.widget.ActiveMediaSessionHolder.activeActivityCount++
        com.lastwave.app.widget.ActiveMediaSessionHolder.isAppForeground = true
    }

    override fun onActivityPaused(activity: Activity) {
        com.lastwave.app.widget.ActiveMediaSessionHolder.activeActivityCount =
            maxOf(0, com.lastwave.app.widget.ActiveMediaSessionHolder.activeActivityCount - 1)
        com.lastwave.app.widget.ActiveMediaSessionHolder.isAppForeground =
            com.lastwave.app.widget.ActiveMediaSessionHolder.activeActivityCount > 0
    }
    // ...
})
```

#### 2. `MusicPlaybackService.kt` Lifecycle & Track Publishing
- **On Service Create**:
  ```kotlin
  ActiveMediaSessionHolder.isPlaybackServiceRunning = true
  ActiveMediaSessionHolder.player = musicPlayer
  ActiveMediaSessionHolder.ownToken = platformSessionToken
  ```
- **On Track Change**:
  ```kotlin
  WidgetUpdater.publish(
      context = this,
      title = track.title,
      artist = track.artist,
      album = track.album,
      sourceApp = getString(R.string.app_name),
      sourcePackage = packageName,
      art = artworkBitmap,
      isPlaying = isPlaying,
      isFavorite = isFav,
      // ...
  )
  ```
- **On Service Destroy**:
  ```kotlin
  ActiveMediaSessionHolder.clearToken(releasedToken)
  ActiveMediaSessionHolder.isPlaybackServiceRunning = false
  ActiveMediaSessionHolder.player = null
  ```
