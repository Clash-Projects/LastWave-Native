package com.lastwave.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationManagerCompat
import com.lastwave.app.R

/**
 * Multi-size responsive RemoteViews factory. One rule: this NEVER throws.
 * Every decode / lookup is guarded and falls back to placeholders, so
 * onUpdate always pushes valid content.
 *
 * Provides dedicated layout archetypes for every slot ratio:
 * - Compact (<220dp width, 1 row, e.g. 2x1): art + title/artist + play.
 * - Standard (>=220dp width, <115dp height, e.g. 3x1, 4x1, 5x1): art + title/artist + play + next.
 * - Square / tall (>=115dp height, e.g. 2x2, 3x2): art + title/artist/EQ row over play pill + next.
 * - Expanded (>=250dp width, >=140dp height, e.g. 4x2, 5x2, 4x3): large art beside title, artist, play pill + next.
 *   140dp is the height of its wrap-content card; shorter cells take the square layout.
 *
 * On Android 12+ (API 31+), responsive size-mapping delivers fluid resizing.
 * On Android 10/11, options-based selection picks the best matching layout.
 */
internal object WidgetViews {

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
        memoryArt: Bitmap? = null,
    ): RemoteViews {
        val resolved = resolve(context)
        val themed = WidgetTheme.resolve(resolved.snapshot.artPath, memoryArt)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizeMap = mapOf(
                SizeF(120f, 50f) to buildLayout(context, R.layout.widget_now_playing_compact, resolved, appWidgetId, eqFrame, themed),
                SizeF(220f, 50f) to buildLayout(context, R.layout.widget_now_playing, resolved, appWidgetId, eqFrame, themed),
                SizeF(120f, 115f) to buildLayout(context, R.layout.widget_now_playing_square, resolved, appWidgetId, eqFrame, themed),
                SizeF(250f, 140f) to buildLayout(context, R.layout.widget_now_playing_expanded, resolved, appWidgetId, eqFrame, themed),
            )
            return RemoteViews(sizeMap)
        }

        // On Android 10/11 fallback using launcher options
        val width = minWidthDp(context, appWidgetId)
        val height = minHeightDp(context, appWidgetId)
        val layoutId = when {
            height >= 140 && width >= 250 -> R.layout.widget_now_playing_expanded
            height >= 115 -> R.layout.widget_now_playing_square
            width in 1 until 220 -> R.layout.widget_now_playing_compact
            else -> R.layout.widget_now_playing
        }
        return buildLayout(context, layoutId, resolved, appWidgetId, eqFrame, themed)
    }

    private fun buildLayout(
        context: Context,
        layoutId: Int,
        resolved: Resolved,
        appWidgetId: Int,
        eqFrame: Int?,
        themed: WidgetTheme.Themed,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        bind(
            context = context,
            views = views,
            resolved = resolved,
            appWidgetId = appWidgetId,
            eqFrame = eqFrame,
            themed = themed,
        )
        return views
    }

    private fun bind(
        context: Context,
        views: RemoteViews,
        resolved: Resolved,
        appWidgetId: Int,
        eqFrame: Int?,
        themed: WidgetTheme.Themed,
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

        views.setTextViewText(
            R.id.widget_title,
            snapshot.title.ifBlank { "Unknown track" },
        )
        views.setTextViewText(
            R.id.widget_subtitle,
            snapshot.artist.ifBlank { "Unknown artist" },
        )
        val playing = snapshot.isPlaying
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

        WidgetTheme.apply(views, themed.colors)
        if (themed.art != null) {
            views.setImageViewBitmap(R.id.widget_art, themed.art)
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
        }

        views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openAppPending(context))
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            WidgetActions.togglePending(context, NowPlayingWidgetReceiver::class.java),
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause_container,
            WidgetActions.togglePending(context, NowPlayingWidgetReceiver::class.java),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            WidgetActions.nextPending(context, NowPlayingWidgetReceiver::class.java),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next_container,
            WidgetActions.nextPending(context, NowPlayingWidgetReceiver::class.java),
        )
    }
}
