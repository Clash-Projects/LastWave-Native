package com.lastwave.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import androidx.glance.GlanceId
import com.lastwave.app.MainActivity
import com.lastwave.app.playback.MusicPlaybackService

object WidgetActions {
    const val ACTION_TOGGLE = "com.lastwave.app.widget.action.TOGGLE"
    const val ACTION_NEXT = "com.lastwave.app.widget.action.NEXT"
    const val ACTION_PREV = "com.lastwave.app.widget.action.PREV"
    const val ACTION_FAVORITE = "com.lastwave.app.widget.action.FAVORITE"
    const val ACTION_SHUFFLE = "com.lastwave.app.widget.action.SHUFFLE"
    const val ACTION_REPEAT = "com.lastwave.app.widget.action.REPEAT"

    const val EXTRA_OPEN_NOW_PLAYING = "open_now_playing"

    private const val REQ_TOGGLE = 101
    private const val REQ_NEXT = 102
    private const val REQ_PREV = 103
    private const val REQ_OPEN_APP = 104
    private const val REQ_FAVORITE = 105
    private const val REQ_SHUFFLE = 106
    private const val REQ_REPEAT = 107
    private const val REQ_OPEN_ACCESS = 108

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun actionPending(context: Context, receiver: Class<*>, action: String, reqCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reqCode,
            Intent(context, receiver).setAction(action),
            flags(),
        )

    fun togglePending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_TOGGLE, REQ_TOGGLE)

    fun nextPending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_NEXT, REQ_NEXT)

    fun prevPending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_PREV, REQ_PREV)

    fun favoritePending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_FAVORITE, REQ_FAVORITE)

    fun shufflePending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_SHUFFLE, REQ_SHUFFLE)

    fun repeatPending(context: Context, receiver: Class<*>): PendingIntent =
        actionPending(context, receiver, ACTION_REPEAT, REQ_REPEAT)

    fun openNowPlayingPending(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_NOW_PLAYING, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            flags(),
        )

    fun openAppPending(context: Context): PendingIntent =
        openNowPlayingPending(context)

    fun openAccessPending(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN_ACCESS,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags(),
        )

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

    suspend fun performToggle(context: Context, glanceId: GlanceId? = null) {
        if (!isAppInBackground(context)) {
            runCatching { openAppPending(context).send() }
            return
        }

        val livePlayer = ActiveMediaSessionHolder.player
        val current = WidgetSnapshot.read(context)
        val wasPlaying = livePlayer?.state?.value?.isPlaying ?: current.isPlaying
        val newPlaying = !wasPlaying

        if (current.title.isNotBlank()) {
            WidgetUpdater.setPlaybackState(context, isPlaying = newPlaying, isBuffering = newPlaying)
        }

        if (livePlayer != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                livePlayer.togglePlayPause()
            }
        } else {
            val controller = resolveController(context)
            if (controller != null) {
                if (wasPlaying) controller.transportControls.pause() else controller.transportControls.play()
            } else {
                sendServiceAction(context, ACTION_TOGGLE)
            }
        }
    }

    suspend fun performSkip(context: Context, next: Boolean, glanceId: GlanceId? = null) {
        if (!isAppInBackground(context)) {
            runCatching { openAppPending(context).send() }
            return
        }

        val livePlayer = ActiveMediaSessionHolder.player
        if (livePlayer != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (next) livePlayer.next() else livePlayer.previous()
            }
        } else {
            val controller = resolveController(context)
            if (controller != null) {
                if (next) controller.transportControls.skipToNext() else controller.transportControls.skipToPrevious()
            } else {
                sendServiceAction(context, if (next) ACTION_NEXT else ACTION_PREV)
            }
        }
    }

    fun isAppInBackground(context: Context): Boolean {
        if (ActiveMediaSessionHolder.isPlaybackServiceRunning) return true
        if (ActiveMediaSessionHolder.isAppForeground) return true
        if (ActiveMediaSessionHolder.activeActivityCount > 0) return true
        if (ActiveMediaSessionHolder.player != null) return true
        if (ActiveMediaSessionHolder.controller != null) return true
        if (ActiveMediaSessionHolder.ownToken != null) return true
        if (resolveController(context) != null) return true
        return false
    }

    fun isFavoriteActionAvailable(context: Context, snapshot: WidgetSnapshot = WidgetSnapshot.read(context)): Boolean {
        // 1. Default screen: no song loaded to like
        if (snapshot.title.isBlank()) {
            return false
        }

        // 2. App must be running in background to register the like
        return isAppInBackground(context)
    }

    suspend fun performFavorite(context: Context, glanceId: GlanceId? = null) {
        val current = WidgetSnapshot.read(context)
        if (!isFavoriteActionAvailable(context, current)) {
            runCatching { openAppPending(context).send() }
            return
        }

        val liveLiked = ActiveMediaSessionHolder.likedSongsManager
        val liveTrack = ActiveMediaSessionHolder.player?.state?.value?.current
        val trackToLike = if (liveTrack != null) {
            com.lastwave.app.data.generate.GeneratedTrack(
                name = liveTrack.title,
                artist = liveTrack.artist,
                album = liveTrack.album,
                artworkUrl = liveTrack.artworkUrl,
            )
        } else if (current.title.isNotBlank()) {
            com.lastwave.app.data.generate.GeneratedTrack(
                name = current.title,
                artist = current.artist,
                album = current.album,
                artworkUrl = current.artPath,
            )
        } else null

        if (liveLiked != null && trackToLike != null) {
            val loved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                liveLiked.toggle(trackToLike)
            }
            WidgetUpdater.setFavorite(context, loved)
        } else {
            sendServiceAction(context, ACTION_FAVORITE)
        }
    }

    suspend fun performShuffle(context: Context, glanceId: GlanceId? = null) {
        sendServiceAction(context, ACTION_SHUFFLE)
    }

    suspend fun performRepeat(context: Context, glanceId: GlanceId? = null) {
        sendServiceAction(context, ACTION_REPEAT)
    }

    private const val SERVICE_ACTION_PREVIOUS = "com.lastwave.app.playback.PREVIOUS"
    private const val SERVICE_ACTION_TOGGLE = "com.lastwave.app.playback.TOGGLE"
    private const val SERVICE_ACTION_NEXT = "com.lastwave.app.playback.NEXT"
    private const val SERVICE_ACTION_FAVORITE = "com.lastwave.app.playback.FAVORITE"
    private const val SERVICE_ACTION_SHUFFLE = "com.lastwave.app.playback.SHUFFLE"
    private const val SERVICE_ACTION_REPEAT = "com.lastwave.app.playback.REPEAT"

    fun sendServiceAction(context: Context, action: String) {
        val serviceAction = when (action) {
            ACTION_TOGGLE -> SERVICE_ACTION_TOGGLE
            ACTION_NEXT -> SERVICE_ACTION_NEXT
            ACTION_PREV -> SERVICE_ACTION_PREVIOUS
            ACTION_FAVORITE -> SERVICE_ACTION_FAVORITE
            ACTION_SHUFFLE -> SERVICE_ACTION_SHUFFLE
            ACTION_REPEAT -> SERVICE_ACTION_REPEAT
            else -> action
        }
        runCatching {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                this.action = serviceAction
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startService(intent)
                } catch (_: IllegalStateException) {
                    context.startForegroundService(intent)
                }
            } else {
                context.startService(intent)
            }
        }
    }
}
