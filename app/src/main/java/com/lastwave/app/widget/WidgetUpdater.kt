package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "WidgetUpdater"
private const val ART_FILE_NAME = "widget_now_playing_art.png"

/**
 * From-scratch widget publisher: plain SharedPreferences + AppWidgetManager.
 *
 * Same public API as before (publish / clear / setPlaying / sync /
 * refreshTheme) so MediaScrobbleListenerService, MusicPlaybackService,
 * MusicPlayer and LastWaveApplication keep compiling unchanged — but the
 * inside is dependency-free: no Glance, no Hilt, no theme repo. A push can
 * therefore never fail the way provideGlance used to, and the widget's
 * initialLayout is real content, so "stuck on loading" is impossible.
 */
object WidgetUpdater {

    // Kept for source compatibility (old Glance widget read it for its
    // equalizer ticker). The new static widget has no animation frames.
    @Volatile
    var animationFrame: Int = 0
        private set

    /** No-op kept for compatibility; the static widget needs no ticker. */
    fun startWaveAnimation(context: Context) = Unit

    /** No-op kept for compatibility; the static widget needs no ticker. */
    fun stopWaveAnimation() = Unit

    // Snapshot write + widget push stay atomic: the playback service and
    // the scrobble listener publish from different threads, and an
    // interleaved write/push pair could otherwise leave stale content on
    // screen with no later event to repair it.
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
        val artPath = art?.let { bitmap -> writeArt(context, bitmap) }
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
            ),
        )
        pushAll(context)
    }

    suspend fun clear(context: Context) = publishMutex.withLock {
        val current = WidgetSnapshot.read(context)
        WidgetSnapshot.write(
            context,
            current.copy(artPath = null, isPlaying = false, hasSession = false),
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
        WidgetSnapshot.write(context, current.copy(isPlaying = isPlaying))
        pushAll(context)
    }

    /** Refreshes freshly placed widgets from persisted state. */
    suspend fun sync(context: Context) {
        pushAll(context)
    }

    /** Re-pushes all widgets (theme change is handled by day/night resources). */
    suspend fun refreshTheme(context: Context) {
        pushAll(context)
    }

    private fun pushAll(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            pushOne(context, manager, NowPlayingWidgetReceiver::class.java, small = true)
            pushOne(context, manager, LargeNowPlayingWidgetReceiver::class.java, small = false)
        }.onFailure { Log.w(TAG, "widget push failed", it) }
    }

    private fun pushOne(
        context: Context,
        manager: AppWidgetManager,
        receiver: Class<*>,
        small: Boolean,
    ) {
        runCatching {
            val ids = manager.getAppWidgetIds(ComponentName(context, receiver))
            for (appWidgetId in ids) {
                val views = if (small) {
                    WidgetViews.buildSmall(context, receiver, appWidgetId)
                } else {
                    WidgetViews.buildLarge(context, receiver, appWidgetId)
                }
                runCatching { manager.updateAppWidget(appWidgetId, views) }
            }
        }.onFailure { Log.w(TAG, "widget push failed for $receiver", it) }
    }

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
