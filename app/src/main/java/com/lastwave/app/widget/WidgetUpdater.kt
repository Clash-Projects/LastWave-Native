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

    @Volatile
    private var lastSanitizedSongKey: String? = null

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
        lastSanitizedSongKey = null
        ActiveMediaSessionHolder.currentArtwork = null
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
            val path = getOrWriteSanitizedArt(context, art, songKey)
            ActiveMediaSessionHolder.currentArtwork = AlbumArtBitmapCache.get(songKey) ?: art
            path
        } else {
            val cached = AlbumArtBitmapCache.get(songKey)
            if (cached != null) {
                ActiveMediaSessionHolder.currentArtwork = cached
                getOrWriteSanitizedArt(context, cached, songKey)
            } else {
                ActiveMediaSessionHolder.currentArtwork = null
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
        if (songKey.isNotBlank() && lastSanitizedSongKey == songKey && AlbumArtBitmapCache.get(songKey) != null && finalFile.exists()) {
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
        if (songKey.isNotBlank()) {
            lastSanitizedSongKey = songKey
        }

        context.filesDir.listFiles { _, name ->
            name.startsWith("widget_art_") && name != "widget_art_current.jpg" && name != "widget_art_current.tmp"
        }?.forEach { runCatching { it.delete() } }

        path
    }.onFailure { Log.w(TAG, "failed to cache widget art", it) }.getOrNull()
}
