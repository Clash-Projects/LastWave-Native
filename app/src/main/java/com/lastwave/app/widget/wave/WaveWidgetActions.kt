package com.lastwave.app.widget.wave

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.lastwave.app.MainShellActivity
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.TrackLikeController
import com.lastwave.app.data.repository.SongPlayStatsRepository
import com.lastwave.app.playback.AndroidAutoMediaLibrary
import com.lastwave.app.playback.MusicPlaybackService
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.widget.ActiveMediaSessionHolder
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object WaveWidgetActions {
    const val ACTION_TOGGLE = "com.lastwave.app.wave.action.TOGGLE"
    const val ACTION_NEXT = "com.lastwave.app.wave.action.NEXT"
    const val ACTION_PREVIOUS = "com.lastwave.app.wave.action.PREVIOUS"
    const val ACTION_LIKE = "com.lastwave.app.wave.action.LIKE"
    const val ACTION_SHUFFLE = "com.lastwave.app.wave.action.SHUFFLE"
    const val ACTION_REPEAT = "com.lastwave.app.wave.action.REPEAT"
    const val ACTION_JUMP = "com.lastwave.app.wave.action.JUMP"
    const val ACTION_RECENT = "com.lastwave.app.wave.action.RECENT"
    const val ACTION_PLAYLIST = "com.lastwave.app.wave.action.PLAYLIST"

    const val PLAYLIST_LIKED = "liked"
    const val PLAYLIST_RECENT = "recent"
    const val PLAYLIST_MOST_PLAYED = "most_played"
    const val PLAYLIST_USER_PREFIX = "user:"

    internal const val EXTRA_TARGET = "wave_target"
    private const val OPEN_REQUEST = 0x5741

    fun pending(context: Context, widgetId: Int, action: String, target: String? = null): PendingIntent {
        val identity = "$widgetId|$action|${target.orEmpty()}"
        val intent = Intent(context, WaveWidgetActionReceiver::class.java).apply {
            this.action = action
            data = Uri.Builder()
                .scheme("lastwave")
                .authority("wave-widget")
                .appendPath(widgetId.toString())
                .appendPath(action.substringAfterLast('.'))
                .appendPath(target.orEmpty())
                .build()
            putExtra(EXTRA_TARGET, target)
        }
        return PendingIntent.getBroadcast(
            context,
            stableRequestCode(identity),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun openPending(context: Context, widgetId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_REQUEST + widgetId,
        Intent(context, MainShellActivity::class.java)
            .setAction(WaveWidgetNavigation.ACTION_OPEN_NOW_PLAYING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun searchPending(context: Context, appWidgetId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        stableRequestCode("$appWidgetId|search"),
        Intent(context, MainShellActivity::class.java)
            .setAction(Intent.ACTION_SEARCH)
            .setData(Uri.parse("lastwave://wave-widget/$appWidgetId/search"))
            .putExtra("widget_navigation", "search")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stableRequestCode(value: String): Int = value.fold(17) { result, char -> result * 31 + char.code }
}

internal data class WaveActionRequest(val action: String, val target: String? = null)

internal interface WaveActionTarget {
    fun hasRestoredSession(): Boolean
    fun restoreSession()
    suspend fun toggle()
    suspend fun next()
    suspend fun previous()
    suspend fun like()
    suspend fun shuffle()
    suspend fun repeat()
    suspend fun jump(queueIndex: Int)
    suspend fun recent(trackId: String) {}
    suspend fun playlist(id: String)
}

internal class WaveActionExecutor(private val target: WaveActionTarget) {
    suspend fun execute(request: WaveActionRequest) {
        if (request.action != WaveWidgetActions.ACTION_PLAYLIST && !target.hasRestoredSession()) {
            target.restoreSession()
        }
        when (request.action) {
            WaveWidgetActions.ACTION_TOGGLE -> target.toggle()
            WaveWidgetActions.ACTION_NEXT -> target.next()
            WaveWidgetActions.ACTION_PREVIOUS -> target.previous()
            WaveWidgetActions.ACTION_LIKE -> target.like()
            WaveWidgetActions.ACTION_SHUFFLE -> target.shuffle()
            WaveWidgetActions.ACTION_REPEAT -> target.repeat()
            WaveWidgetActions.ACTION_JUMP -> request.target?.toIntOrNull()?.let { target.jump(it) }
            WaveWidgetActions.ACTION_RECENT -> request.target?.let { target.recent(it) }
            WaveWidgetActions.ACTION_PLAYLIST -> request.target?.let { target.playlist(it) }
        }
    }
}

internal object WaveActionDebouncer {
    private val acceptedAt = ConcurrentHashMap<String, Long>()

    fun shouldAccept(action: String, target: String?, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val window = when (action) {
            WaveWidgetActions.ACTION_LIKE -> 450L
            WaveWidgetActions.ACTION_TOGGLE -> 220L
            else -> 0L
        }
        if (window == 0L) return true
        val key = "$action|${target.orEmpty()}"
        val previous = acceptedAt.put(key, nowMs)
        if (previous == null || nowMs - previous >= window) return true
        acceptedAt[key] = previous
        return false
    }

    internal fun clear() = acceptedAt.clear()
}

internal object WaveLikeOptimism {
    fun optimistic(state: WaveWidgetState): WaveWidgetState = state.copy(isLiked = !state.isLiked)

    fun settle(original: WaveWidgetState, optimistic: WaveWidgetState, confirmed: Boolean?): WaveWidgetState =
        when (confirmed) {
            null -> original
            else -> optimistic.copy(isLiked = confirmed)
        }
}

@AndroidEntryPoint
class WaveWidgetActionReceiver : android.content.BroadcastReceiver() {
    @Inject lateinit var musicPlayer: MusicPlayer
    @Inject lateinit var likeController: TrackLikeController
    @Inject lateinit var playlistRepository: PlaylistRepository
    @Inject lateinit var statsRepository: SongPlayStatsRepository
    @Inject lateinit var androidAutoLibrary: AndroidAutoMediaLibrary

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val target = intent.getStringExtra(WaveWidgetActions.EXTRA_TARGET)
        if (!WaveActionDebouncer.shouldAccept(action, target)) return
        val pendingResult = goAsync()
        ACTION_SCOPE.launch {
            try {
                ACTION_MUTEX.withLock {
                    execute(context.applicationContext, WaveActionRequest(action, target))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "Wave widget action failed: $action", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun execute(context: Context, request: WaveActionRequest) {
        val target = object : WaveActionTarget {
            override fun hasRestoredSession(): Boolean = musicPlayer.state.value.current != null
            override fun restoreSession() {
                musicPlayer.restoreSessionIfNeeded()
            }

            override suspend fun toggle() {
                if (dispatchController(context, request.action, null)) return
                if (dispatchMediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) return
                ensurePlaybackService(context)
                musicPlayer.togglePlayPause()
            }

            override suspend fun next() {
                if (dispatchController(context, request.action, null)) return
                if (dispatchMediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT)) return
                ensurePlaybackService(context)
                musicPlayer.next()
            }

            override suspend fun previous() {
                if (dispatchController(context, request.action, null)) return
                if (dispatchMediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) return
                ensurePlaybackService(context)
                musicPlayer.previous()
            }

            override suspend fun like() = toggleLike(context)

            override suspend fun shuffle() {
                if (dispatchController(context, request.action, null)) return
                musicPlayer.toggleShuffle()
            }

            override suspend fun repeat() {
                if (dispatchController(context, request.action, null)) return
                musicPlayer.cycleRepeatMode()
            }

            override suspend fun jump(queueIndex: Int) {
                if (dispatchController(context, request.action, queueIndex)) return
                ensurePlaybackService(context)
                musicPlayer.seekToQueueItem(queueIndex)
            }

            override suspend fun playlist(id: String) {
                ensurePlaybackService(context)
                playPlaylist(id)
            }

            override suspend fun recent(trackId: String) {
                val remembered = WaveWidgetState.read(context).recentTracks
                    .firstOrNull { it.trackId == trackId } ?: return
                // A historical item has no live queue index. Prefer the exact
                // restored queue metadata, then let normal playback resolve it.
                val track = musicPlayer.state.value.queue.firstOrNull { it.waveTrackId() == trackId }
                    ?: remembered.toPlayableTrack()
                ensurePlaybackService(context)
                musicPlayer.play(track, sourceLabel = "Recent", startRadio = false)
            }
        }
        WaveActionExecutor(target).execute(request)
        WaveWidgetUpdater.refresh(context)
    }

    private suspend fun toggleLike(context: Context) {
        val track = musicPlayer.state.value.current ?: return
        val original = WaveWidgetState.read(context)
        val optimistic = WaveLikeOptimism.optimistic(original)
        WaveWidgetUpdater.updateState(context, optimistic)
        val confirmed = runCatching { likeController.toggle(track) }.getOrNull()
        WaveWidgetUpdater.updateState(context, WaveLikeOptimism.settle(original, optimistic, confirmed))
    }

    private suspend fun playPlaylist(id: String) {
        when (id) {
            WaveWidgetActions.PLAYLIST_LIKED -> {
                val playlist = playlistRepository.getLikedSongs() ?: return
                androidAutoLibrary.playMediaId("playlist:${playlist.id}", musicPlayer)
            }
            WaveWidgetActions.PLAYLIST_RECENT -> {
                val tracks = statsRepository.recentlyPlayed(50).map { stat ->
                    PlayableTrack(stat.title, stat.artist, artworkUrl = stat.artworkUrl, videoId = stat.videoId)
                }
                if (tracks.isNotEmpty()) musicPlayer.playQueue(tracks, sourceLabel = "Recent")
            }
            WaveWidgetActions.PLAYLIST_MOST_PLAYED -> {
                val tracks = statsRepository.mostPlayedSince(days = 3650, limit = 50).map { stat ->
                    PlayableTrack(stat.title, stat.artist, artworkUrl = stat.artworkUrl, videoId = stat.videoId)
                }
                if (tracks.isNotEmpty()) musicPlayer.playQueue(tracks, sourceLabel = "Most played")
            }
            else -> id.removePrefix(WaveWidgetActions.PLAYLIST_USER_PREFIX).toLongOrNull()?.let { playlistId ->
                androidAutoLibrary.playMediaId("playlist:$playlistId", musicPlayer)
            }
        }
    }

    private fun dispatchController(context: Context, action: String, queueIndex: Int?): Boolean {
        val token = ActiveMediaSessionHolder.ownToken ?: return false
        return runCatching {
            val controller = MediaController(context, token)
            when (action) {
                WaveWidgetActions.ACTION_TOGGLE -> when (controller.playbackState?.state) {
                    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> controller.transportControls.pause()
                    else -> controller.transportControls.play()
                }
                WaveWidgetActions.ACTION_NEXT -> controller.transportControls.skipToNext()
                WaveWidgetActions.ACTION_PREVIOUS -> controller.transportControls.skipToPrevious()
                // The framework TransportControls surface has no public
                // shuffle/repeat setters; those actions fall through to the
                // app's own player, which is the source of truth for both.
                WaveWidgetActions.ACTION_SHUFFLE,
                WaveWidgetActions.ACTION_REPEAT,
                -> return false
                WaveWidgetActions.ACTION_JUMP -> controller.transportControls.skipToQueueItem(queueIndex?.toLong() ?: return false)
                else -> return false
            }
            true
        }.getOrDefault(false)
    }

    private fun dispatchMediaButton(context: Context, keyCode: Int): Boolean = runCatching {
        val serviceIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, context, MusicPlaybackService::class.java).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            putExtra(EXTRA_WAVE_WIDGET, true)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
        true
    }.getOrDefault(false)

    private fun ensurePlaybackService(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, MusicPlaybackService::class.java))
        }
    }

    companion object {
        private const val TAG = "WaveWidgetAction"
        const val EXTRA_WAVE_WIDGET = "from_wave_widget"
        private val ACTION_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val ACTION_MUTEX = Mutex()
    }
}

internal fun WaveQueueTrack.toPlayableTrack(): PlayableTrack = PlayableTrack(
    title = title,
    artist = artist,
    artworkUrl = artworkUri,
    videoId = videoId ?: trackId.takeIf { it.startsWith("video:") }?.removePrefix("video:"),
    internalTrackId = trackId.takeIf { it.startsWith("internal:") }?.removePrefix("internal:"),
    playbackUrl = playbackUri,
)
