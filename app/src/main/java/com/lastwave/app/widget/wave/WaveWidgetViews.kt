package com.lastwave.app.widget.wave

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.lastwave.app.R

/** Render each launcher orientation using its actual available dimensions. */
object WaveWidgetViews {
    fun build(
        context: Context,
        appWidgetId: Int,
        kind: WaveWidgetKind,
        state: WaveWidgetState,
        art: WaveWidgetArt.Result = WaveWidgetArt.Result(null, null, null),
    ): RemoteViews {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val fallback = when (kind) {
            WaveWidgetKind.STRIP -> SizeF(300f, 72f)
            WaveWidgetKind.GLASS, WaveWidgetKind.CIRCULAR -> SizeF(160f, 160f)
            WaveWidgetKind.LIBRARY -> SizeF(288f, 144f)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
                ?.distinct()?.take(4).orEmpty()
            if (sizes.isNotEmpty()) {
                return RemoteViews(sizes.associateWith { buildSize(context, appWidgetId, kind, state, art, it) })
            }
        }
        fun dimension(key: String, default: Float) =
            options.getInt(key).takeIf { it > 0 }?.toFloat() ?: default
        val portrait = SizeF(
            dimension(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallback.width),
            dimension(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, fallback.height),
        )
        val landscape = SizeF(
            dimension(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, portrait.width),
            dimension(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, portrait.height),
        )
        return RemoteViews(
            buildSize(context, appWidgetId, kind, state, art, landscape),
            buildSize(context, appWidgetId, kind, state, art, portrait),
        )
    }

    private fun buildSize(
        context: Context,
        widgetId: Int,
        kind: WaveWidgetKind,
        state: WaveWidgetState,
        art: WaveWidgetArt.Result,
        size: SizeF,
    ): RemoteViews {
        val layout = if (kind == WaveWidgetKind.LIBRARY) {
            if (size.width >= 240f)
                R.layout.widget_wave_library else R.layout.widget_wave_library_compact
        } else if (kind == WaveWidgetKind.CIRCULAR && minOf(size.width, size.height) < 145f) {
            R.layout.widget_wave_circular_compact
        } else {
            kind.layoutRes
        }
        val views = RemoteViews(context.packageName, layout)
        val colors = art.colors ?: WaveWidgetColors.derive(0xFF555466.toInt(), dark = true)
        val stripBackground = WaveColorMath.stripBackground(colors.accent)
        val foreground = if (kind == WaveWidgetKind.STRIP) 0xFFFFFFFF.toInt() else 0xFFF6F4FF.toInt()
        val secondary = if (kind == WaveWidgetKind.STRIP) colors.muted else 0xFFD8D4E6.toInt()
        views.setOnClickPendingIntent(R.id.wave_widget_root, WaveWidgetActions.openPending(context, widgetId))
        views.setTextViewText(R.id.wave_title, state.title.takeIf { state.hasTrack }
            ?: context.getString(R.string.wave_widget_nothing_playing))
        views.setTextColor(R.id.wave_title, foreground)
        if (kind != WaveWidgetKind.STRIP) {
            views.setTextViewText(R.id.wave_artist, state.artist.takeIf { state.hasTrack && it.isNotBlank() }
                ?: context.getString(R.string.wave_widget_tap_to_open))
            views.setTextColor(R.id.wave_artist, secondary)
        }
        bindControls(context, views, widgetId, kind, state, foreground, colors.accent)

        when (kind) {
            WaveWidgetKind.STRIP -> {
                val side = minOf(size.height, size.width)
                views.setImageViewBitmap(R.id.wave_surface_image,
                    WaveWidgetGraphics.strip(null, stripBackground, size.width, size.height, 0f))
                // The launcher measures a real square cover beside the controls; a stretched
                // background bitmap can no longer move the art boundary underneath them.
                views.setImageViewBitmap(R.id.wave_art, WaveWidgetGraphics.stripArtwork(art.art, stripBackground))
                val slotWidth = (size.width - side) / 5f
                val controlHeight = size.height * 0.64f
                val mainIconSide = minOf(size.height * 0.35f, slotWidth * 0.82f)
                val edgeIconSide = minOf(size.height * 0.27f, slotWidth * 0.73f)
                fun fitIcon(id: Int, iconSide: Float) {
                    val horizontal = dp(context, (slotWidth - iconSide).coerceAtLeast(0f) / 2f)
                    val vertical = dp(context, (controlHeight - iconSide).coerceAtLeast(0f) / 2f)
                    views.setViewPadding(id, horizontal, vertical, horizontal, vertical)
                }
                intArrayOf(R.id.wave_previous_icon, R.id.wave_play_icon, R.id.wave_next_icon)
                    .forEach { fitIcon(it, mainIconSide) }
                intArrayOf(R.id.wave_like_icon, R.id.wave_shuffle_icon)
                    .forEach { fitIcon(it, edgeIconSide) }
                views.setImageViewResource(R.id.wave_play_icon,
                    if (state.isPlaying) R.drawable.ic_wave_strip_pause else R.drawable.ic_wave_strip_play)
                views.setTextViewTextSize(R.id.wave_title, TypedValue.COMPLEX_UNIT_SP,
                    (size.height * 0.205f).coerceIn(10f, 20f))
                views.setViewPadding(R.id.wave_title, dp(context, side * 0.20f),
                    0, dp(context, maxOf(20f, side * 0.20f)), 0)
                views.setInt(R.id.wave_logo, "setColorFilter", foreground)
                views.setViewVisibility(R.id.wave_play_background, View.GONE)
            }
            WaveWidgetKind.GLASS -> {
                art.backdrop?.let { views.setImageViewBitmap(R.id.wave_surface_image, it) }
                views.setImageViewBitmap(R.id.wave_progress, WaveWidgetGraphics.progress(state.progressAt()))
                views.setTextViewTextSize(R.id.wave_title, TypedValue.COMPLEX_UNIT_SP,
                    if (size.height < 145) 14f else 18f)
                views.setTextViewTextSize(R.id.wave_artist, TypedValue.COMPLEX_UNIT_SP,
                    if (size.height < 145) 11f else 13f)
                views.setInt(R.id.wave_logo, "setColorFilter", foreground)
            }
            WaveWidgetKind.CIRCULAR -> {
                val side = minOf(size.width, size.height)
                // Keep the artwork, wave ring and controls together on rectangular launcher cells.
                views.setViewPadding(R.id.wave_widget_root,
                    dp(context, (size.width - side) / 2), dp(context, (size.height - side) / 2),
                    dp(context, (size.width - side) / 2), dp(context, (size.height - side) / 2))
                views.setImageViewBitmap(R.id.wave_surface_image,
                    WaveWidgetGraphics.circular(art.art, colors.accent, state.progressAt()))
                views.setTextViewTextSize(R.id.wave_title, TypedValue.COMPLEX_UNIT_SP,
                    if (side < 145) 12f else 15f)
                views.setTextViewTextSize(R.id.wave_artist, TypedValue.COMPLEX_UNIT_SP,
                    if (side < 145) 10f else 11f)
                views.setViewVisibility(R.id.wave_play_background, View.GONE)
            }
            WaveWidgetKind.LIBRARY -> bindLibrary(context, views, widgetId, state, art, size, layout)
        }
        return views
    }

    private fun bindControls(
        context: Context, views: RemoteViews, widgetId: Int, kind: WaveWidgetKind,
        state: WaveWidgetState, foreground: Int, accent: Int,
    ) {
        val bindings = listOf(
            Triple(R.id.wave_previous, R.id.wave_previous_icon, WaveWidgetActions.ACTION_PREVIOUS),
            Triple(R.id.wave_play, R.id.wave_play_icon, WaveWidgetActions.ACTION_TOGGLE),
            Triple(R.id.wave_next, R.id.wave_next_icon, WaveWidgetActions.ACTION_NEXT),
        )
        bindings.forEach { (view, icon, action) ->
            views.setOnClickPendingIntent(view, WaveWidgetActions.pending(context, widgetId, action))
            views.setInt(icon, "setColorFilter", foreground)
        }
        views.setImageViewResource(R.id.wave_play_icon,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        views.setCharSequence(R.id.wave_play, "setContentDescription",
            context.getString(if (state.isPlaying) R.string.wave_widget_pause else R.string.wave_widget_play))
        if (kind != WaveWidgetKind.CIRCULAR) {
            views.setImageViewResource(R.id.wave_like_icon,
                if (state.isLiked) R.drawable.ic_wave_widget_favorite_filled else R.drawable.ic_wave_widget_favorite)
            views.setInt(R.id.wave_like_icon, "setColorFilter",
                if (kind != WaveWidgetKind.STRIP && state.isLiked) 0xFFE2D9FF.toInt() else foreground)
            views.setCharSequence(R.id.wave_like, "setContentDescription",
                context.getString(if (state.isLiked) R.string.wave_widget_unlike else R.string.wave_widget_like))
            views.setOnClickPendingIntent(R.id.wave_like,
                WaveWidgetActions.pending(context, widgetId, WaveWidgetActions.ACTION_LIKE))
        }
        if (kind == WaveWidgetKind.STRIP || kind == WaveWidgetKind.GLASS) {
            views.setInt(R.id.wave_shuffle_icon, "setColorFilter",
                if (kind != WaveWidgetKind.STRIP && state.shuffleEnabled)
                    WaveColorMath.mix(accent, foreground, 0.7f) else foreground)
            views.setCharSequence(R.id.wave_shuffle, "setContentDescription",
                context.getString(if (state.shuffleEnabled) R.string.wave_widget_shuffle_on
                    else R.string.wave_widget_shuffle))
            views.setOnClickPendingIntent(R.id.wave_shuffle,
                WaveWidgetActions.pending(context, widgetId, WaveWidgetActions.ACTION_SHUFFLE))
        }
        if (kind == WaveWidgetKind.GLASS || kind == WaveWidgetKind.LIBRARY) {
            views.setImageViewBitmap(R.id.wave_play_background, WaveWidgetGraphics.playButton())
            views.setInt(R.id.wave_play_icon, "setColorFilter", 0xFF171629.toInt())
        }
    }

    private fun bindLibrary(
        context: Context, views: RemoteViews, widgetId: Int, state: WaveWidgetState,
        art: WaveWidgetArt.Result, size: SizeF, layout: Int,
    ) {
        art.art?.let { views.setImageViewBitmap(R.id.wave_art, WaveWidgetGraphics.rounded(it)) }
            ?: views.setImageViewResource(R.id.wave_art, R.drawable.wave_widget_preview_art)
        val position = (state.progressAt().toDouble() * state.durationMs).toLong()
        views.setTextViewText(R.id.wave_time, if (state.hasTrack)
            "${formatTime(position)} / ${formatTime(state.durationMs)}" else "— / —")
        views.setTextViewTextSize(R.id.wave_title, TypedValue.COMPLEX_UNIT_SP,
            if (layout == R.layout.widget_wave_library_compact) 11f
            else if (size.height < 128f) 12f else 13f)
        views.setOnClickPendingIntent(R.id.wave_search, WaveWidgetActions.searchPending(context, widgetId))
        val shortcut = state.playlists.firstOrNull()
        views.setTextViewText(R.id.wave_playlist_label, shortcut?.let {
            when (it.id) {
                WaveWidgetActions.PLAYLIST_LIKED -> context.getString(R.string.wave_widget_playlist_liked)
                WaveWidgetActions.PLAYLIST_RECENT -> context.getString(R.string.wave_widget_playlist_recent)
                WaveWidgetActions.PLAYLIST_MOST_PLAYED -> context.getString(R.string.wave_widget_playlist_most_played)
                else -> it.title
            }
        } ?: context.getString(R.string.wave_widget_playlist_shortcut))
        views.setOnClickPendingIntent(R.id.wave_playlist_shortcut,
            WaveWidgetActions.pending(context, widgetId, WaveWidgetActions.ACTION_PLAYLIST,
                shortcut?.id ?: WaveWidgetActions.PLAYLIST_LIKED))
        val containers = intArrayOf(R.id.wave_recent_1, R.id.wave_recent_2, R.id.wave_recent_3, R.id.wave_recent_4)
        val images = intArrayOf(R.id.wave_recent_art_1, R.id.wave_recent_art_2, R.id.wave_recent_art_3, R.id.wave_recent_art_4)
        val titles = intArrayOf(R.id.wave_recent_title_1, R.id.wave_recent_title_2, R.id.wave_recent_title_3, R.id.wave_recent_title_4)
        val recent = state.recentTracks.take(4)
        views.setViewVisibility(R.id.wave_recent_empty, if (recent.isEmpty()) View.VISIBLE else View.GONE)
        containers.forEachIndexed { index, viewId ->
            val track = recent.getOrNull(index)
            // Keep history thumbnails in equal columns, even with fewer than four tracks.
            views.setViewVisibility(viewId, if (track == null) View.INVISIBLE else View.VISIBLE)
            if (track != null) {
                views.setTextViewText(titles[index], track.title)
                art.recentArt[track.trackId]?.let {
                    views.setImageViewBitmap(images[index], WaveWidgetGraphics.circle(it))
                } ?: views.setImageViewResource(images[index], R.drawable.wave_widget_recent_placeholder)
                views.setCharSequence(viewId, "setContentDescription", "${track.title} · ${track.artist}")
                views.setOnClickPendingIntent(viewId,
                    WaveWidgetActions.pending(context, widgetId, WaveWidgetActions.ACTION_RECENT, track.trackId))
            }
        }
        if (layout != R.layout.widget_wave_library) return
        val nextContainers = intArrayOf(R.id.wave_next_1, R.id.wave_next_2, R.id.wave_next_3, R.id.wave_next_4)
        val nextImages = intArrayOf(R.id.wave_next_art_1, R.id.wave_next_art_2, R.id.wave_next_art_3, R.id.wave_next_art_4)
        val next = state.nextTracks.take(4)
        views.setViewVisibility(R.id.wave_next_empty, if (next.isEmpty()) View.VISIBLE else View.GONE)
        nextContainers.forEachIndexed { index, viewId ->
            val track = next.getOrNull(index)
            views.setViewVisibility(viewId, if (track == null) View.INVISIBLE else View.VISIBLE)
            if (track != null) {
                art.nextArt[track.trackId]?.let {
                    views.setImageViewBitmap(nextImages[index], WaveWidgetGraphics.circle(it))
                } ?: views.setImageViewResource(nextImages[index], R.drawable.wave_widget_recent_placeholder)
                views.setCharSequence(viewId, "setContentDescription", "${track.title} · ${track.artist}")
                views.setOnClickPendingIntent(viewId,
                    WaveWidgetActions.pending(context, widgetId, WaveWidgetActions.ACTION_JUMP,
                        track.queueIndex.toString()))
            }
        }
    }

    private fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
