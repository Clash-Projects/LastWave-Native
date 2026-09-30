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
