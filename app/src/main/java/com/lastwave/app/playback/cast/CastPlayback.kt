package com.lastwave.app.playback.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CastPlayback(
    private val context: Context,
    private val owner: MusicPlayer,
    private val scope: CoroutineScope,
    private val castContext: CastContext,
) {
    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var server: CastStreamServer? = null
    private var loadJob: Job? = null
    private var contentId: String? = null
    private var requestedPlaying = true
    private var loading = false
    val active: Boolean get() = session != null

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = updateState()
        override fun onMetadataUpdated() = updateState()
    }
    private val progress = RemoteMediaClient.ProgressListener { _, _ -> updateState() }
    private val castListener = object : com.google.android.gms.cast.Cast.Listener() {
        override fun onVolumeChanged() {
            val s = session ?: return
            val vol = runCatching { s.volume.toFloat() }.getOrNull() ?: return
            owner.onCastVolumeChanged(vol)
        }
    }
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) = attach(session)
        override fun onSessionResumed(session: CastSession, suspended: Boolean) = attach(session)
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            owner.castError("Could not connect to Chromecast ($error)")
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            loadJob?.cancel()
            owner.castError("Chromecast connection interrupted")
        }
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, id: String) = Unit
    }

    fun initialize() {
        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        castContext.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
    }

    private fun attach(newSession: CastSession) {
        client?.unregisterCallback(callback)
        client?.removeProgressListener(progress)
        session?.removeCastListener(castListener)
        session = newSession
        newSession.addCastListener(castListener)
        val initialVol = runCatching { newSession.volume.toFloat() }.getOrNull()
        if (initialVol != null) {
            owner.onCastVolumeChanged(initialVol)
        }
        client = newSession.remoteMediaClient
        client?.registerCallback(callback)
        client?.addProgressListener(progress, 500)
        val snapshot = owner.prepareForCast()
        load(snapshot, snapshot.isPlaying)
    }

    fun load(snapshot: MusicPlayerState, autoplay: Boolean = true) {
        val track = snapshot.current ?: return
        loadJob?.cancel()
        contentId = null
        loading = true
        requestedPlaying = autoplay
        owner.castBuffering(autoplay)
        client?.stop()
        loadJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val resolved = owner.resolveCastStream(track)
                val streamServer = server ?: createServer()
                val url = streamServer.publish(resolved)
                contentId = url
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                    putString(MediaMetadata.KEY_TITLE, track.title)
                    putString(MediaMetadata.KEY_ARTIST, track.artist)
                    track.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
                    track.artworkUrl?.takeIf { it.startsWith("https://") }?.let { addImage(WebImage(Uri.parse(it))) }
                }
                val media = MediaInfo.Builder(url)
                    .setContentType(resolved.mimeType.substringBefore(';'))
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setMetadata(metadata)
                    .apply { if (snapshot.durationMs > 0) setStreamDuration(snapshot.durationMs) }
                    .build()
                val remote = client ?: return@launch
                remote.load(MediaLoadRequestData.Builder().setMediaInfo(media)
                    .setAutoplay(requestedPlaying).setCurrentTime(snapshot.positionMs)
                    .setPlaybackRate(snapshot.speed.toDouble()).build())
                    .setResultCallback { result ->
                        if (contentId == url) {
                            loading = false
                            if (!result.status.isSuccess) {
                                owner.castError("Chromecast could not play this audio (${result.status.statusCode})")
                            } else {
                                if (!requestedPlaying) remote.pause()
                                updateState()
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                loading = false
                owner.castError(error.message ?: "Could not cast this track")
            }
        }
    }

    fun play() {
        requestedPlaying = true
        if (!loading) {
            if (contentId != null && client?.hasMediaSession() == true) client?.play()
            else load(owner.state.value.copy(positionMs = 0))
        }
    }

    fun pause() {
        requestedPlaying = false
        client?.pause()
    }

    fun setSpeed(speed: Float) {
        client?.setPlaybackRate(speed.toDouble())
    }

    fun seek(positionMs: Long) {
        if (loading) {
            load(owner.state.value.copy(positionMs = positionMs), requestedPlaying)
        } else {
            client?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
        }
    }

    fun setVolume(volume: Float) {
        val s = session ?: return
        runCatching {
            s.setVolume(volume.toDouble().coerceIn(0.0, 1.0))
        }.onFailure { android.util.Log.w("CastPlayback", "Could not set Cast volume", it) }
    }

    fun disconnect() = castContext.sessionManager.endCurrentSession(true)

    private fun updateState() {
        val remote = client ?: return
        val expected = contentId ?: return
        if (loading || remote.mediaInfo?.contentId != expected) return
        owner.updateCastState(remote.isPlaying, remote.isBuffering,
            remote.approximateStreamPosition, remote.streamDuration,
            remote.mediaStatus?.playbackRate?.toFloat() ?: 1f)
        if (remote.playerState == MediaStatus.PLAYER_STATE_IDLE) {
            when (remote.idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> {
                    contentId = null
                    owner.castTrackEnded()
                }
                MediaStatus.IDLE_REASON_ERROR -> {
                    contentId = null
                    owner.castError("This audio could not be played on Chromecast")
                }
            }
        }
    }

    private fun detach() {
        loadJob?.cancel()
        client?.unregisterCallback(callback)
        client?.removeProgressListener(progress)
        session?.removeCastListener(castListener)
        client = null
        session = null
        contentId = null
        loading = false
        val previousServer = server
        server = null
        scope.launch(Dispatchers.IO) { previousServer?.stop() }
        owner.finishCasting()
    }

    private suspend fun createServer(): CastStreamServer {
        var created: CastStreamServer? = null
        try {
            return withContext(Dispatchers.IO) { CastStreamServer.create(context).also { created = it } }
                .also { server = it }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { created?.stop() }
            throw error
        }
    }
}
