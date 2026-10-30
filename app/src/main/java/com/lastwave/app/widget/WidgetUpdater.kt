package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "WidgetUpdater"
private const val ART_FILE_NAME = "widget_now_playing_art.png"

/**
 * Single-widget publisher: plain SharedPreferences + AppWidgetManager.
 *
 * Same public API as before (publish / clear / setPlaying / sync /
 * refreshTheme) so MusicPlaybackService,
 * MusicPlayer and LastWaveApplication keep compiling unchanged — but the
 * inside is dependency-free: no Glance, no Hilt, no theme repo.
 *
 * The equalizer/progress live ticker was removed (audit #9): re-pushing
 * every 600ms burned CPU/battery for a cosmetic progress bar. Pushes now
 * happen only on discrete playback/metadata events.
 */
object WidgetUpdater {

    @Volatile
    var animationFrame: Int = 0
        private set

    @Volatile
    var memoryArtBitmap: Bitmap? = null
        internal set

    // Live ticker removed (audit #9). The functions stay as no-ops so
    // MusicPlaybackService / MusicPlayer callers keep compiling unchanged.
    fun startWaveAnimation(@Suppress("UNUSED_PARAMETER") context: Context) {
        // no-op
    }

    /** No-op: ticker removed. Kept for API compatibility. */
    fun stopWaveAnimation() {
        // no-op
    }

    // Snapshot write + widget push stay atomic: the playback service and
    // the scrobble listener publish from different threads, and an
    // interleaved write/push pair could otherwise leave stale content on
    // screen with no later event to repair it. The ticker re-push stays
    // outside the mutex (display-only overrides, no disk writes).
    private val publishMutex = Mutex()

    suspend fun publish(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        sourceApp: String,
        sourcePackage: String,
        art: Bitmap?,
        isPlaying: Boolean,
    ) = publishMutex.withLock {
        memoryArtBitmap = art
        val currentSnapshot = WidgetSnapshot.read(context)
        val artPath = art?.let { bitmap -> writeArt(context, bitmap) }
            ?: if (currentSnapshot.title == title && currentSnapshot.artist == artist) currentSnapshot.artPath else null
        // Snapshot the position now: the controller is fresh at publish time.
        val fraction = readLiveFraction(context, 0f)
        WidgetSnapshot.write(
            context,
            WidgetSnapshot(
                title = title,
                artist = artist,
                album = album.orEmpty(),
                sourceApp = sourceApp,
                sourcePackage = sourcePackage,
                artPath = artPath,
                isPlaying = isPlaying,
                hasSession = true,
                progress = fraction,
            ),
        )
        pushAll(context)
        if (isPlaying) startWaveAnimation(context) else stopWaveAnimation()
    }

    suspend fun clear(context: Context) = publishMutex.withLock {
        memoryArtBitmap = null
        stopWaveAnimation()
        val current = WidgetSnapshot.read(context)
        WidgetSnapshot.write(
            context,
            current.copy(artPath = null, isPlaying = false, hasSession = false, progress = 0f),
        )
        pushAll(context)
    }

    /**
     * Immediately reflects widget-originated playback actions while the
     * media-session callback catches up. Always writes and pushes so a
     * stale persisted flag can never leave the play/pause glyph out of
     * sync with the real session.
     */
    suspend fun setPlaying(context: Context, isPlaying: Boolean) = publishMutex.withLock {
        val current = WidgetSnapshot.read(context)
        if (!current.hasSession) return@withLock
        WidgetSnapshot.write(
            context,
            current.copy(isPlaying = isPlaying, progress = readLiveFraction(context, current.progress)),
        )
        pushAll(context)
        if (isPlaying) startWaveAnimation(context) else stopWaveAnimation()
    }

    /** Refreshes a freshly placed widget from persisted state. */
    suspend fun sync(context: Context) {
        pushAll(context)
    }

    /** Re-pushes the widget (theme change is handled by day/night resources). */
    suspend fun refreshTheme(context: Context) {
        pushAll(context)
    }

    /**
     * Pushes the single widget to every placed id. Optional overrides let a
     * caller render a specific frame/progress without touching disk. Returns
     * false when nothing is placed.
     */
    private fun pushAll(context: Context, eqFrame: Int? = null, progressOverride: Float? = null): Boolean =
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetReceiver::class.java))
            for (appWidgetId in ids) {
                val views = WidgetViews.build(context, appWidgetId, eqFrame, progressOverride, memoryArtBitmap)
                runCatching { manager.updateAppWidget(appWidgetId, views) }
            }
            val obsidianIds = manager.getAppWidgetIds(ComponentName(context, ObsidianGlassWidgetReceiver::class.java))
            for (appWidgetId in obsidianIds) {
                val views = ObsidianWidgetViews.build(context, appWidgetId, eqFrame, progressOverride, memoryArtBitmap)
                runCatching { manager.updateAppWidget(appWidgetId, views) }
            }
            ids.isNotEmpty() || obsidianIds.isNotEmpty()
        }.onFailure { Log.w(TAG, "widget push failed", it) }.getOrDefault(false)

    /** Live position 0..1 off the MediaController, extrapolated while playing. */
    private fun readLiveFraction(context: Context, fallback: Float): Float = runCatching {
        val controller = WidgetActions.resolveController(context) ?: return fallback
        val state = controller.playbackState ?: return fallback
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) return fallback
        val position = if (state.state == PlaybackState.STATE_PLAYING) {
            state.position + (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
        } else {
            state.position
        }
        (position.toFloat() / duration).coerceIn(0f, 1f)
    }.getOrDefault(fallback)

    @Synchronized
    private fun writeArt(context: Context, bitmap: Bitmap): String? = runCatching {
        val file = File(context.filesDir, ART_FILE_NAME)
        val pending = File(context.filesDir, "$ART_FILE_NAME.pending")
        val largest = maxOf(bitmap.width, bitmap.height)
        val cached = if (largest <= 384) bitmap else {
            val scale = 384f / largest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        FileOutputStream(pending).use { out -> cached.compress(Bitmap.CompressFormat.PNG, 90, out) }
        if (cached !== bitmap) cached.recycle()
        if (!pending.renameTo(file)) {
            pending.copyTo(file, overwrite = true)
            pending.delete()
        }
        file.absolutePath
    }.onFailure { Log.w(TAG, "failed to cache widget art", it) }.getOrNull()
}
