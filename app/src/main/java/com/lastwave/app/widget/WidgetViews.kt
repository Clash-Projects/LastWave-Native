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
 * From-scratch RemoteViews factory. One rule: this NEVER throws.
 * Every decode / lookup is guarded and falls back to placeholders, so
 * onUpdate always pushes valid content — the exact opposite of the old
 * Glance path, where one throw meant a permanent loading spinner.
 */
internal object WidgetViews {

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

    // Height breakpoints (dp, from the launcher's own measurement). Below
    // the line the full two-row / showcase layout would clip, so the
    // compact single row (60dp tall) is used instead. Nothing overlaps,
    // nothing clips, on any grid, foldable, or landscape size.
    private const val SMALL_TALL_MIN_DP = 110
    private const val LARGE_TALL_MIN_DP = 340

    /** Reads the launcher's measured allocation for this exact widget id. */
    private fun minHeightDp(context: Context, appWidgetId: Int): Int = runCatching {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
    }.getOrDefault(0)

    fun buildSmall(context: Context, receiver: Class<*>, appWidgetId: Int): RemoteViews {
        val resolved = resolve(context)
        val layout = if (minHeightDp(context, appWidgetId) in 1 until SMALL_TALL_MIN_DP) {
            R.layout.widget_now_playing_compact
        } else {
            R.layout.widget_now_playing
        }
        val views = RemoteViews(context.packageName, layout)
        bindCommon(context, views, receiver, resolved)
        return views
    }

    fun buildLarge(context: Context, receiver: Class<*>, appWidgetId: Int): RemoteViews {
        val resolved = resolve(context)
        // A short large widget gets the horizontal player (same binder, same
        // IDs); only tall placements get the vertical showcase.
        val layout = if (minHeightDp(context, appWidgetId) in 1 until LARGE_TALL_MIN_DP) {
            R.layout.widget_now_playing
        } else {
            R.layout.widget_now_playing_large
        }
        val views = RemoteViews(context.packageName, layout)
        bindCommon(context, views, receiver, resolved)
        return views
    }

    private fun bindCommon(
        context: Context,
        views: RemoteViews,
        receiver: Class<*>,
        resolved: Resolved,
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

        views.setTextViewText(
            R.id.widget_title,
            snapshot.title.ifBlank { "Unknown track" },
        )
        views.setTextViewText(
            R.id.widget_subtitle,
            snapshot.artist.ifBlank { "Unknown artist" },
        )
        val playing = snapshot.isPlaying
        // Layout applies allCaps + letterSpacing; champagne gold while
        // playing, quiet gray while paused.
        views.setTextViewText(
            R.id.widget_state,
            if (playing) "Playing" else "Paused",
        )
        runCatching {
            val stateColor = ContextCompat.getColor(
                context,
                if (playing) R.color.widget_accent else R.color.widget_on_surface_variant,
            )
            views.setTextColor(R.id.widget_state, stateColor)
        }
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        // Prev/next vectors are white (drawn for the red pill); tint them to
        // on-surface-variant so they stay visible on the light circle.
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
        views.setOnClickPendingIntent(R.id.widget_play_pause, WidgetActions.togglePending(context, receiver))
        views.setOnClickPendingIntent(R.id.widget_prev, WidgetActions.prevPending(context, receiver))
        views.setOnClickPendingIntent(R.id.widget_next, WidgetActions.nextPending(context, receiver))
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
