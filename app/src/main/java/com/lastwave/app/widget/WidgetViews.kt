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
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lastwave.app.R
import java.io.File

/**
 * Single-widget RemoteViews factory. One rule: this NEVER throws.
 * Every decode / lookup is guarded and falls back to placeholders, so
 * onUpdate always pushes valid content.
 *
 * One layout ([R.layout.widget_now_playing]) serves every size. The
 * launcher's measured width picks the breakpoint and the binder only
 * toggles visibility — elements are cut at small sizes, never shrunk:
 * - Compact  (<280dp): art + title + play. Artist, prev/next, EQ status
 *   and progress are hidden entirely.
 * - Standard (280–420dp): + artist, prev/next. EQ status + progress hidden.
 * - Expanded (>420dp): everything, incl. animated EQ + progress bar.
 */
internal object WidgetViews {

    private const val COMPACT_MAX_DP = 280
    private const val STANDARD_MAX_DP = 420
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

    fun build(
        context: Context,
        appWidgetId: Int,
        eqFrame: Int? = null,
        progressOverride: Float? = null,
    ): RemoteViews {
        val resolved = resolve(context)
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
        bind(context, views, resolved, appWidgetId, eqFrame, progressOverride)
        return views
    }

    private fun bind(
        context: Context,
        views: RemoteViews,
        resolved: Resolved,
        appWidgetId: Int,
        eqFrame: Int?,
        progressOverride: Float?,
    ) {
        val snapshot = resolved.snapshot
        if (!resolved.usableSession) {
            views.setViewVisibility(R.id.widget_empty_group, View.VISIBLE)
            views.setViewVisibility(R.id.widget_content_group, View.GONE)
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

        // Breakpoint by measured width; unknown (0) degrades to standard —
        // the safe middle that never clips and never hides essentials.
        val width = minWidthDp(context, appWidgetId)
        val compact = width in 1 until COMPACT_MAX_DP
        val expanded = width > STANDARD_MAX_DP

        views.setViewVisibility(R.id.widget_subtitle, if (compact) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_prev, if (compact) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_next, if (compact) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_eq_group, if (expanded) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_progress_row, if (expanded) View.VISIBLE else View.GONE)

        views.setTextViewText(
            R.id.widget_title,
            snapshot.title.ifBlank { "Unknown track" },
        )
        views.setTextViewText(
            R.id.widget_subtitle,
            snapshot.artist.ifBlank { "Unknown artist" },
        )
        val playing = snapshot.isPlaying
        views.setTextViewText(
            R.id.widget_state,
            if (playing) "Playing" else "Paused",
        )
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        // Animated EQ while playing (ticker cycles frames); frozen first
        // frame while paused. Neutral bars — the accent stays reserved for
        // the play button + progress fill.
        runCatching {
            val frame = if (playing) eqFrames[(eqFrame ?: 0).mod(eqFrames.size)]
            else eqFrames[0]
            views.setImageViewResource(R.id.widget_eq_icon, frame)
        }

        // Progress: live ticker override wins, else last persisted fraction.
        runCatching {
            val fraction = (progressOverride ?: snapshot.progress).coerceIn(0f, 1f)
            views.setProgressBar(R.id.widget_progress, PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
        }

        // Prev/next vectors are drawn white (for the red pill); tint them to
        // on-surface-variant so they read as light gray on either theme —
        // never near-black-on-black.
        runCatching {
            val tint = ContextCompat.getColor(context, R.color.widget_on_surface_variant)
            views.setInt(R.id.widget_prev, "setColorFilter", tint)
            views.setInt(R.id.widget_next, "setColorFilter", tint)
        }

        // Artwork: file cache written by WidgetUpdater (max 384px), finished
        // with soft rounded corners (RemoteViews ImageViews can't clip, so
        // the bitmap itself carries the radius). Any failure -> launcher
        // placeholder, never a crash.
        val artSet = runCatching {
            val path = snapshot.artPath
            val file = if (path.isNullOrBlank()) null else File(path)
            if (file != null && file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { decoded ->
                    val art = roundedCorners(decoded, 0.24f)
                    if (art !== decoded) runCatching { decoded.recycle() }
                    views.setImageViewBitmap(R.id.widget_art, art)
                    true
                } ?: false
            } else false
        }.getOrDefault(false)
        if (!artSet) {
            runCatching { views.setImageViewResource(R.id.widget_art, R.drawable.ic_launcher_foreground) }
        }

        views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openAppPending(context))
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            WidgetActions.togglePending(context, NowPlayingWidgetReceiver::class.java),
        )
        views.setOnClickPendingIntent(
            R.id.widget_prev,
            WidgetActions.prevPending(context, NowPlayingWidgetReceiver::class.java),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            WidgetActions.nextPending(context, NowPlayingWidgetReceiver::class.java),
        )
    }

    /**
     * Softens square album art into a rounded card. Pure bitmap math —
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
