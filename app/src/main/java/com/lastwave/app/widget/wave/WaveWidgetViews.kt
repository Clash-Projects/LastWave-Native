package com.lastwave.app.widget.wave

import android.content.Context
import android.content.res.ColorStateList
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.lastwave.app.R

/** Classic RemoteViews renderer shared by all six Wave widget providers. */
object WaveWidgetViews {
    fun build(
        context: Context,
        appWidgetId: Int,
        kind: WaveWidgetKind,
        state: WaveWidgetState,
        art: WaveWidgetArt.Result = WaveWidgetArt.Result(null, null, null),
    ): RemoteViews {
        val views = RemoteViews(context.packageName, kind.layoutRes)
        // Pill and Strip deliberately keep the app/system theme instead of
        // changing color with every album.
        val colors = if (kind == WaveWidgetKind.PILL || kind == WaveWidgetKind.STRIP) {
            WaveWidgetColors.theme(context)
        } else {
            art.colors ?: WaveWidgetColors.theme(context)
        }

        views.setOnClickPendingIntent(
            R.id.wave_widget_root,
            WaveWidgetActions.openPending(context, appWidgetId),
        )
        views.setImageViewBitmap(R.id.wave_surface_image, renderSurface(kind, colors.background))
        bindArtwork(views, art)
        bindPlayback(context, views, appWidgetId, state, colors)

        when (kind) {
            WaveWidgetKind.TURNTABLE -> bindTurntable(views, state, colors)
            WaveWidgetKind.VINYL -> bindVinyl(context, views, appWidgetId, state, art, colors)
            WaveWidgetKind.DECK -> bindDeck(context, views, appWidgetId, state, art, colors)
            WaveWidgetKind.STAGE -> bindStage(context, views, appWidgetId, state, art, colors)
            WaveWidgetKind.PILL -> {
                bindTrackText(context, views, state, colors)
                bindTransport(context, views, appWidgetId, colors, previous = false, next = true)
            }
            WaveWidgetKind.STRIP -> {
                bindTrackText(context, views, state, colors)
                bindTransport(context, views, appWidgetId, colors, previous = true, next = true)
                bindLike(context, views, appWidgetId, state, colors)
                bindWaveProgress(views, state, colors, width = 256, height = 12)
            }
        }
        applyResponsiveVisibility(context, views, appWidgetId, kind)
        return views
    }

    private fun bindArtwork(views: RemoteViews, art: WaveWidgetArt.Result) {
        if (art.art != null && !art.art.isRecycled) {
            views.setImageViewBitmap(R.id.wave_art, art.art)
        } else {
            views.setImageViewResource(R.id.wave_art, R.drawable.wave_widget_art_placeholder)
        }
    }

    private fun bindPlayback(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        state: WaveWidgetState,
        colors: WaveWidgetColors,
    ) {
        views.setImageViewBitmap(
            R.id.wave_play_background,
            renderPlayBackground(colors.accent, state.isPlaying),
        )
        views.setImageViewResource(
            R.id.wave_play_icon,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setInt(R.id.wave_play_icon, "setColorFilter", colors.onAccent)
        views.setOnClickPendingIntent(
            R.id.wave_play,
            WaveWidgetActions.pending(context, appWidgetId, WaveWidgetActions.ACTION_TOGGLE),
        )
        views.setCharSequence(
            R.id.wave_play,
            "setContentDescription",
            context.getString(if (state.isPlaying) R.string.wave_widget_pause else R.string.wave_widget_play),
        )
    }

    private fun bindTurntable(
        views: RemoteViews,
        state: WaveWidgetState,
        colors: WaveWidgetColors,
    ) {
        views.setImageViewBitmap(
            R.id.wave_ring_progress,
            renderRingProgress(state.progressAt(), colors.container, colors.accent),
        )
    }

    private fun bindVinyl(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        state: WaveWidgetState,
        art: WaveWidgetArt.Result,
        colors: WaveWidgetColors,
    ) {
        bindBackdrop(views, art)
        bindTrackText(context, views, state, colors)
        bindTransport(context, views, appWidgetId, colors, previous = false, next = true)
        bindLike(context, views, appWidgetId, state, colors)
        tintBackground(views, R.id.wave_art_ring, colors.container)
    }

    private fun bindDeck(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        state: WaveWidgetState,
        art: WaveWidgetArt.Result,
        colors: WaveWidgetColors,
    ) {
        bindBackdrop(views, art)
        bindTrackText(context, views, state, colors)
        bindTransport(context, views, appWidgetId, colors, previous = true, next = true)
        bindWaveProgress(views, state, colors, width = 256, height = 22)
        bindLike(context, views, appWidgetId, state, colors)
        bindShuffle(context, views, appWidgetId, state, colors)
    }

    private fun bindStage(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        state: WaveWidgetState,
        art: WaveWidgetArt.Result,
        colors: WaveWidgetColors,
    ) {
        bindBackdrop(views, art)
        bindTrackText(context, views, state, colors)
        bindTransport(context, views, appWidgetId, colors, previous = true, next = true)
        bindWaveProgress(views, state, colors, width = 256, height = 22)
        bindLike(context, views, appWidgetId, state, colors)
        bindShuffle(context, views, appWidgetId, state, colors)
        bindRepeat(context, views, appWidgetId, state, colors)

        views.setTextColor(R.id.wave_queue_heading, colors.onBackground)
        views.setTextColor(R.id.wave_playlist_heading, colors.onBackground)
        val queueIds = intArrayOf(R.id.wave_queue_1, R.id.wave_queue_2, R.id.wave_queue_3)
        queueIds.forEachIndexed { index, viewId ->
            val track = state.nextTracks.getOrNull(index)
            views.setViewVisibility(viewId, if (track == null) View.GONE else View.VISIBLE)
            if (track != null) {
                val label = if (track.artist.isBlank()) track.title else "${track.title} \u00b7 ${track.artist}"
                views.setTextViewText(viewId, label)
                views.setTextColor(viewId, colors.onBackground)
                tintBackground(views, viewId, colors.container)
                views.setOnClickPendingIntent(
                    viewId,
                    WaveWidgetActions.pending(
                        context,
                        appWidgetId,
                        WaveWidgetActions.ACTION_JUMP,
                        track.queueIndex.toString(),
                    ),
                )
            }
        }

        val playlistIds = intArrayOf(
            R.id.wave_playlist_1,
            R.id.wave_playlist_2,
            R.id.wave_playlist_3,
            R.id.wave_playlist_4,
            R.id.wave_playlist_5,
        )
        state.playlists.take(5).forEachIndexed { index, playlist ->
            val viewId = playlistIds[index]
            views.setViewVisibility(viewId, View.VISIBLE)
            views.setTextViewText(viewId, playlist.title)
            views.setTextColor(viewId, colors.onBackground)
            tintBackground(views, viewId, colors.container)
            views.setOnClickPendingIntent(
                viewId,
                WaveWidgetActions.pending(
                    context,
                    appWidgetId,
                    WaveWidgetActions.ACTION_PLAYLIST,
                    playlist.id,
                ),
            )
        }
        for (index in state.playlists.take(5).size until playlistIds.size) {
            views.setViewVisibility(playlistIds[index], View.GONE)
        }
    }

    private fun bindTrackText(
        context: Context,
        views: RemoteViews,
        state: WaveWidgetState,
        colors: WaveWidgetColors,
    ) {
        views.setTextViewText(
            R.id.wave_title,
            state.title.takeIf { state.hasTrack } ?: context.getString(R.string.wave_widget_nothing_playing),
        )
        views.setTextViewText(
            R.id.wave_artist,
            state.artist.takeIf { state.hasTrack && it.isNotBlank() }
                ?: context.getString(R.string.wave_widget_tap_to_open),
        )
        views.setTextColor(R.id.wave_title, colors.onBackground)
        views.setTextColor(R.id.wave_artist, colors.muted)
    }

    private fun bindTransport(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        colors: WaveWidgetColors,
        previous: Boolean,
        next: Boolean,
    ) {
        if (previous) {
            views.setInt(R.id.wave_previous_icon, "setColorFilter", colors.onBackground)
            tintBackground(views, R.id.wave_previous, colors.container)
            views.setOnClickPendingIntent(
                R.id.wave_previous,
                WaveWidgetActions.pending(context, appWidgetId, WaveWidgetActions.ACTION_PREVIOUS),
            )
        }
        if (next) {
            views.setInt(R.id.wave_next_icon, "setColorFilter", colors.onBackground)
            tintBackground(views, R.id.wave_next, colors.container)
            views.setOnClickPendingIntent(
                R.id.wave_next,
                WaveWidgetActions.pending(context, appWidgetId, WaveWidgetActions.ACTION_NEXT),
            )
        }
    }

    private fun bindLike(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        state: WaveWidgetState,
        colors: WaveWidgetColors,
    ) {
        views.setImageViewResource(
            R.id.wave_like_icon,
            if (state.isLiked) R.drawable.ic_wave_widget_favorite_filled else R.drawable.ic_wave_widget_favorite,
        )
        views.setInt(
            R.id.wave_like_icon,
            "setColorFilter",
            if (state.isLiked) colors.accent else colors.onBackground,
        )
        tintBackground(views, R.id.wave_like, colors.container)
        views.setOnClickPendingIntent(
            R.id.wave_like,
            WaveWidgetActions.pending(context, appWidgetId, WaveWidgetActions.ACTION_LIKE),
        )
    }

    private fun bindShuffle(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        state: WaveWidgetState,
        colors: WaveWidgetColors,
    ) {
        views.setInt(
            R.id.wave_shuffle_icon,
            "setColorFilter",
            if (state.shuffleEnabled) colors.accent else colors.onBackground,
        )
        tintBackground(views, R.id.wave_shuffle, colors.container)
        views.setOnClickPendingIntent(
            R.id.wave_shuffle,
            WaveWidgetActions.pending(context, appWidgetId, WaveWidgetActions.ACTION_SHUFFLE),
        )
    }

    private fun bindRepeat(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        state: WaveWidgetState,
        colors: WaveWidgetColors,
    ) {
        views.setImageViewResource(
            R.id.wave_repeat_icon,
            if (state.repeatMode == 1) R.drawable.ic_widget_repeat_one else R.drawable.ic_widget_repeat,
        )
        views.setInt(
            R.id.wave_repeat_icon,
            "setColorFilter",
            if (state.repeatMode != 0) colors.accent else colors.onBackground,
        )
        tintBackground(views, R.id.wave_repeat, colors.container)
        views.setOnClickPendingIntent(
            R.id.wave_repeat,
            WaveWidgetActions.pending(context, appWidgetId, WaveWidgetActions.ACTION_REPEAT),
        )
    }

    private fun bindWaveProgress(
        views: RemoteViews,
        state: WaveWidgetState,
        colors: WaveWidgetColors,
        width: Int,
        height: Int,
    ) {
        views.setImageViewBitmap(
            R.id.wave_progress,
            renderWaveProgress(width, height, state.progressAt(), colors.container, colors.accent),
        )
    }

    private fun bindBackdrop(views: RemoteViews, art: WaveWidgetArt.Result) {
        val backdrop = art.backdrop
        views.setViewVisibility(R.id.wave_backdrop, if (backdrop == null) View.GONE else View.VISIBLE)
        if (backdrop != null && !backdrop.isRecycled) views.setImageViewBitmap(R.id.wave_backdrop, backdrop)
    }

    private fun tintBackground(views: RemoteViews, viewId: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                viewId,
                "setBackgroundTintList",
                ColorStateList.valueOf(color),
            )
        }
    }

    private fun applyResponsiveVisibility(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        kind: WaveWidgetKind,
    ) {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val fallback = when (kind) {
            WaveWidgetKind.TURNTABLE -> 96 to 96
            WaveWidgetKind.VINYL -> 180 to 180
            WaveWidgetKind.DECK -> 300 to 150
            WaveWidgetKind.STAGE -> 300 to 300
            WaveWidgetKind.PILL -> 220 to 64
            WaveWidgetKind.STRIP -> 320 to 64
        }
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallback.first)
            .takeIf { it > 0 } ?: fallback.first
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, fallback.second)
            .takeIf { it > 0 } ?: fallback.second

        when (kind) {
            WaveWidgetKind.TURNTABLE -> Unit
            WaveWidgetKind.VINYL -> {
                views.setViewVisibility(R.id.wave_artist, if (height >= 145) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_like, if (width >= 150) View.VISIBLE else View.GONE)
            }
            WaveWidgetKind.DECK -> {
                views.setViewVisibility(R.id.wave_artist, if (height >= 112) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_shuffle, if (width >= 260) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_like, if (width >= 230) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_progress, if (height >= 100) View.VISIBLE else View.GONE)
            }
            WaveWidgetKind.STAGE -> {
                val showModes = width >= 250
                views.setViewVisibility(R.id.wave_shuffle, if (showModes) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_repeat, if (showModes) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_like, if (showModes) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_artist, if (height >= 225) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_stage_lists, if (height >= 220) View.VISIBLE else View.GONE)
                views.setViewVisibility(
                    R.id.wave_playlist_column,
                    if (height >= 285 && width >= 250) View.VISIBLE else View.GONE,
                )
            }
            WaveWidgetKind.PILL -> {
                views.setViewVisibility(R.id.wave_artist, if (width >= 220) View.VISIBLE else View.GONE)
            }
            WaveWidgetKind.STRIP -> {
                views.setViewVisibility(R.id.wave_artist, if (width >= 310) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_progress, if (width >= 260) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wave_like, if (width >= 285) View.VISIBLE else View.GONE)
            }
        }
    }

    private fun renderSurface(kind: WaveWidgetKind, color: Int): Bitmap {
        val (width, height, radius) = when (kind) {
            WaveWidgetKind.TURNTABLE -> Triple(128, 128, 30f)
            WaveWidgetKind.VINYL -> Triple(160, 160, 32f)
            WaveWidgetKind.DECK -> Triple(256, 128, 28f)
            WaveWidgetKind.STAGE -> Triple(192, 192, 24f)
            WaveWidgetKind.PILL -> Triple(256, 64, 32f)
            WaveWidgetKind.STRIP -> Triple(256, 64, 22f)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
            )
        }
    }

    private fun renderPlayBackground(color: Int, isPlaying: Boolean): Bitmap {
        val size = 64
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            if (isPlaying) {
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            } else {
                canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 19f, 19f, paint)
            }
        }
    }

    private fun renderRingProgress(progress: Float, trackColor: Int, playedColor: Int): Bitmap {
        val size = 128
        val stroke = 9f
        val bounds = RectF(stroke, stroke, size - stroke, size - stroke)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = trackColor
            }
            canvas.drawArc(bounds, -90f, 360f, false, paint)
            val playedSweep = 360f * progress.coerceIn(0f, 1f)
            if (playedSweep > 0f) {
                canvas.drawArc(
                    bounds,
                    -90f,
                    playedSweep,
                    false,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 5f
                        strokeCap = Paint.Cap.ROUND
                        color = playedColor
                        alpha = 120
                        setShadowLayer(7f, 0f, 0f, playedColor)
                    },
                )
            }
            paint.color = playedColor
            canvas.drawArc(bounds, -90f, playedSweep, false, paint)
        }
    }

    private fun renderWaveProgress(
        width: Int,
        height: Int,
        progress: Float,
        trackColor: Int,
        playedColor: Int,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val center = height / 2f
        canvas.drawLine(
            0f,
            center,
            width.toFloat(),
            center,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = trackColor
                strokeWidth = 2f
                strokeCap = Paint.Cap.ROUND
            },
        )

        val playedEdge = width * progress.coerceIn(0f, 1f)
        val points = WaveProgress.points(width, height, progress, sampleCount = 72)
            .takeWhile { it.x <= playedEdge + 0.5f }
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { point -> lineTo(point.x, point.y) }
                lineTo(playedEdge, center)
            }
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = playedColor
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                },
            )
        }
    }
}
