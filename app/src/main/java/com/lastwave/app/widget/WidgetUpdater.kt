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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "WidgetUpdater"
private const val ART_FILE_NAME = "widget_now_playing_art.png"
private const val TICK_INTERVAL_MS = 600L

/**
 * Single-widget publisher: plain SharedPreferences + AppWidgetManager.
 *
 * Same public API as before (publish / clear / setPlaying / sync /
 * refreshTheme) so MediaScrobbleListenerService, MusicPlaybackService,
 * MusicPlayer and LastWaveApplication keep compiling unchanged — but the
 * inside is dependency-free: no Glance, no Hilt, no theme repo.
 *
 * While playing, a light 600ms ticker re-pushes only the equalizer frame
 * and the live progress fraction (read off the MediaController, never
 * written to disk). It stops on pause/clear or when no widget is placed.
 */
object WidgetUpdater {

    @Volatile
    var animationFrame: Int = 0
        private set

    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var tickerJob: Job? = null

    /** Starts the equalizer/progress ticker for active playback (idempotent). */
    fun startWaveAnimation(context: Context) {
        synchronized(this) {
            if (tickerJob?.isActive == true) return
            val app = context.applicationContext
            tickerJob = tickerScope.launch {
                while (isActive) {
                    delay(TICK_INTERVAL_MS)
                    animationFrame = (animationFrame + 1) % 3
                    val snapshot = WidgetSnapshot.read(app)
                    if (!snapshot.hasSession || !snapshot.isPlaying) {
                        stopWaveAnimation()
                        return@launch
                    }
                    val fraction = readLiveFraction(app, snapshot.progress)
                    if (!pushAll(app, eqFrame = animationFrame, progressOverride = fraction)) break
                }
            }
        }
    }

    /** Stops the equalizer/progress ticker. */
    fun stopWaveAnimation() {
        synchronized(this) {
            tickerJob?.cancel()
            tickerJob = null
        }
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
        val artPath = art?.let { bitmap -> writeArt(context, bitmap) }
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
     * Pushes the single widget to every placed id. Optional overrides drive
     * the live ticker without touching disk. Returns false when nothing is
     * placed (ticker stops itself then).
     */
    private fun pushAll(context: Context, eqFrame: Int? = null, progressOverride: Float? = null): Boolean =
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetReceiver::class.java))
            for (appWidgetId in ids) {
                val views = WidgetViews.build(context, appWidgetId, eqFrame, progressOverride)
                runCatching { manager.updateAppWidget(appWidgetId, views) }
            }
            ids.isNotEmpty()
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
