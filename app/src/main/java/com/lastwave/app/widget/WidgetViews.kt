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
        val cacheKey = "rounded_${path}_${file.lastModified()}"
        val cached = AlbumArtBitmapCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) return cached

        BitmapFactory.decodeFile(file.absolutePath)?.let { decoded ->
            val art = roundedCorners(decoded, 0.22f)
            if (art !== decoded) runCatching { decoded.recycle() }
            AlbumArtBitmapCache.put(cacheKey, art)
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
