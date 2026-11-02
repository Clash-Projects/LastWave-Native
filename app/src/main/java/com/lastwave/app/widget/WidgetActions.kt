package com.lastwave.app.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import com.lastwave.app.MainActivity
import kotlinx.coroutines.delay

/**
 * From-scratch widget controls.
 *
 * The old Glance widget used ActionCallback (runs inside Glance's own
 * service, needs Glance + Hilt on the path). The new widget is plain
 * RemoteViews, so every button is a broadcast PendingIntent back to its
 * own receiver — no library, nothing that can leave the widget loading.
 */
object WidgetActions {
    const val ACTION_TOGGLE = "com.lastwave.app.widget.action.TOGGLE"
    const val ACTION_NEXT = "com.lastwave.app.widget.action.NEXT"
    const val ACTION_PREV = "com.lastwave.app.widget.action.PREV"

    private const val REQ_TOGGLE = 101
    private const val REQ_NEXT = 102
    private const val REQ_PREV = 103
    private const val REQ_OPEN_APP = 104
    private const val REQ_OPEN_ACCESS = 105

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun togglePending(context: Context, receiver: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_TOGGLE,
            Intent(context, receiver).setAction(ACTION_TOGGLE),
            flags(),
        )

    fun nextPending(context: Context, receiver: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_NEXT,
            Intent(context, receiver).setAction(ACTION_NEXT),
            flags(),
        )

    fun prevPending(context: Context, receiver: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_PREV,
            Intent(context, receiver).setAction(ACTION_PREV),
            flags(),
        )

    fun openAppPending(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags(),
        )

    fun openAccessPending(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN_ACCESS,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags(),
        )

    // ---- media transport (same resolution order as the old widget) ----

    internal fun resolveController(context: Context): MediaController? {
        val held = ActiveMediaSessionHolder.controller
        held?.let {
            val state = runCatching { it.playbackState?.state }.getOrNull()
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) return it
        }
        return held ?: ActiveMediaSessionHolder.ownToken?.let { token ->
            runCatching { MediaController(context, token) }.getOrNull()
        }
    }

    /**
     * Toggle play/pause and optimistically flip the widget glyph so the
     * button responds instantly; when playback state changes, the service
     * publishes the confirmed state.
     */
    suspend fun performToggle(context: Context) {
        val controller = resolveController(context) ?: return
        val snapshot = WidgetSnapshot.read(context)
        val state = runCatching { controller.playbackState?.state }.getOrNull()
        val isCurrentlyPlaying = when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> false
            else -> snapshot.isPlaying
        }
        val targetPlaying = !isCurrentlyPlaying
        val succeeded = runCatching {
            if (isCurrentlyPlaying) controller.transportControls.pause()
            else controller.transportControls.play()
        }.isSuccess
        if (!succeeded) return
        WidgetUpdater.setPlaying(context, targetPlaying)
    }

    suspend fun performSkip(context: Context, next: Boolean) {
        val controller = resolveController(context) ?: return
        runCatching {
            if (next) controller.transportControls.skipToNext()
            else controller.transportControls.skipToPrevious()
        }
    }
}
