package com.lastwave.app.playback.jam

import android.os.SystemClock
import android.util.Log
import com.lastwave.app.data.jam.JamProtocolConstants
import com.lastwave.app.data.jam.JamRepository
import com.lastwave.app.data.jam.JamSessionState
import com.lastwave.app.data.jam.RemotePlaybackEvent
import com.lastwave.app.data.jam.TrackInfoPayload
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Service-level synchronization bridge for LastWave Jam sessions.
 *
 * Runs as an application-scoped singleton to guarantee that playback synchronization
 * continues uninterrupted across the entire app lifecycle, surviving backgrounding,
 * screen off, and navigation away from Jam sheets/screens.
 */
@Singleton
class JamPlaybackSync @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val jamRepository: JamRepository,
) {
    companion object {
        private const val TAG = "JamPlaybackSync"
        private const val DRIFT_SEEK_THRESHOLD_MS = 1500L
        private const val MAX_LATENCY_COMPENSATION_MS = 3000L
        private val YOUTUBE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var quietUntilMs = 0L
    private var lastBroadcastTrackId: String? = null
    private var lastBroadcastIsPlaying: Boolean? = null
    private var lastBroadcastPositionMs = 0L
    private var loadingTrackId: String? = null

    init {
        start()
    }

    fun start() {
        // 1. Observe session state changes to reset broadcast history
        scope.launch {
            jamRepository.sessionState.collectLatest { session ->
                if (session !is JamSessionState.Active) {
                    lastBroadcastTrackId = null
                    lastBroadcastIsPlaying = null
                    lastBroadcastPositionMs = 0L
                    loadingTrackId = null
                    quietUntilMs = 0L
                } else {
                    Log.d(TAG, "Jam session active (${session.roomCode}), host=${session.isHost}")
                }
            }
        }

        // 2. Inbound: Listen for remote playback events and apply to MusicPlayer
        scope.launch {
            jamRepository.remotePlaybackEvents.collect { event ->
                val session = jamRepository.sessionState.value as? JamSessionState.Active ?: return@collect

                // Echo loop suppression: ignore events that originated from ourselves
                if (event.fromUserId != null && event.fromUserId == session.userId) {
                    return@collect
                }

                // Inbound events from other peers should never be dropped by quietUntilMs,
                // otherwise play/seek events immediately following a track change get discarded.
                handleRemoteEvent(event, session)
            }
        }

        // 3. Outbound: Listen for local MusicPlayer changes and broadcast to Jam
        scope.launch {
            musicPlayer.state
                .map { StateSnapshot(it.isPlaying, it.current) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    val session = jamRepository.sessionState.value as? JamSessionState.Active ?: return@collect

                    // Only host or collaborative participants can control playback
                    val canControl = session.isHost || session.roomMode == JamProtocolConstants.MODE_COLLABORATIVE
                    if (!canControl) {
                        return@collect
                    }

                    // If local change was triggered by remote event application, suppress
                    if (SystemClock.elapsedRealtime() < quietUntilMs) {
                        return@collect
                    }

                    val current = snapshot.current
                    val isPlaying = snapshot.isPlaying
                    val pos = musicPlayer.progressState.value.positionMs

                    if (current != null) {
                        val trackId = current.videoId?.takeIf { it.isNotBlank() } ?: current.title
                        val trackChanged = trackId != lastBroadcastTrackId
                        val playStateChanged = isPlaying != lastBroadcastIsPlaying

                        if (trackChanged) {
                            lastBroadcastTrackId = trackId
                            lastBroadcastIsPlaying = isPlaying
                            lastBroadcastPositionMs = pos
                            quietUntilMs = SystemClock.elapsedRealtime() + 1000L

                            val payload = TrackInfoPayload(
                                id = trackId,
                                title = current.title,
                                artist = current.artist,
                                album = current.album,
                                artworkUrl = current.artworkUrl,
                                durationMs = current.durationMs,
                            )
                            jamRepository.broadcastChangeTrack(payload, positionMs = pos, isPlaying = isPlaying)
                        } else if (playStateChanged) {
                            // Do not broadcast pause if the player is simply buffering or resolving a stream
                            if (!isPlaying && musicPlayer.state.value.isBuffering) {
                                return@collect
                            }
                            lastBroadcastIsPlaying = isPlaying
                            lastBroadcastPositionMs = pos
                            quietUntilMs = SystemClock.elapsedRealtime() + 800L

                            if (isPlaying) {
                                jamRepository.broadcastPlay(pos)
                            } else {
                                jamRepository.broadcastPause(pos)
                            }
                        }
                    } else if (isPlaying != lastBroadcastIsPlaying) {
                        if (!isPlaying && musicPlayer.state.value.isBuffering) {
                            return@collect
                        }
                        lastBroadcastIsPlaying = isPlaying
                        val currentPos = musicPlayer.progressState.value.positionMs
                        lastBroadcastPositionMs = currentPos
                        quietUntilMs = SystemClock.elapsedRealtime() + 800L

                        if (isPlaying) {
                            jamRepository.broadcastPlay(currentPos)
                        } else {
                            jamRepository.broadcastPause(currentPos)
                        }
                    }
                }
        }
    }

    private fun handleRemoteEvent(event: RemotePlaybackEvent, session: JamSessionState.Active) {
        when (event) {
            is RemotePlaybackEvent.ChangeTrack -> {
                applyTrackChange(event.track, event.positionMs, event.isPlaying)
            }
            is RemotePlaybackEvent.Play -> {
                val now = System.currentTimeMillis()
                val latency = if (event.serverTimeMs > 0 && now > event.serverTimeMs) {
                    (now - event.serverTimeMs).coerceAtMost(MAX_LATENCY_COMPENSATION_MS)
                } else 0L
                val targetPos = (event.positionMs + latency).coerceAtLeast(0L)

                val localCurrent = musicPlayer.state.value.current
                if (localCurrent == null && session.currentTrack != null) {
                    applyTrackChange(session.currentTrack, targetPos, isPlaying = true)
                } else {
                    quietUntilMs = SystemClock.elapsedRealtime() + 800L
                    val currentPos = musicPlayer.progressState.value.positionMs
                    if (abs(currentPos - targetPos) > DRIFT_SEEK_THRESHOLD_MS) {
                        musicPlayer.seekTo(targetPos)
                    }
                    if (!musicPlayer.state.value.isPlaying) {
                        musicPlayer.resume()
                    }
                }
            }
            is RemotePlaybackEvent.Pause -> {
                quietUntilMs = SystemClock.elapsedRealtime() + 800L
                if (musicPlayer.state.value.isPlaying) {
                    musicPlayer.pause()
                }
                if (event.positionMs >= 0) {
                    val currentPos = musicPlayer.progressState.value.positionMs
                    if (abs(currentPos - event.positionMs) > DRIFT_SEEK_THRESHOLD_MS) {
                        musicPlayer.seekTo(event.positionMs)
                    }
                }
            }
            is RemotePlaybackEvent.Seek -> {
                quietUntilMs = SystemClock.elapsedRealtime() + 800L
                val currentPos = musicPlayer.progressState.value.positionMs
                if (abs(currentPos - event.positionMs) > DRIFT_SEEK_THRESHOLD_MS) {
                    musicPlayer.seekTo(event.positionMs)
                }
            }
            is RemotePlaybackEvent.FullSync -> {
                val remoteTrack = event.playback.currentTrack
                if (remoteTrack != null) {
                    val now = System.currentTimeMillis()
                    val latency = if (event.playback.serverTimeMs > 0 && now > event.playback.serverTimeMs) {
                        (now - event.playback.serverTimeMs).coerceAtMost(MAX_LATENCY_COMPENSATION_MS)
                    } else 0L
                    val targetPos = (event.playback.positionMs + latency).coerceAtLeast(0L)
                    applyTrackChange(remoteTrack, targetPos, event.playback.isPlaying)
                }
            }
        }
    }

    private fun applyTrackChange(remoteTrack: TrackInfoPayload, positionMs: Long, isPlaying: Boolean) {
        val localCurrent = musicPlayer.state.value.current
        val sameSong = isSameTrack(localCurrent, remoteTrack)

        if (!sameSong) {
            if (loadingTrackId == remoteTrack.id) return
            loadingTrackId = remoteTrack.id

            quietUntilMs = SystemClock.elapsedRealtime() + 2500L
            lastBroadcastTrackId = remoteTrack.id
            lastBroadcastIsPlaying = isPlaying
            lastBroadcastPositionMs = positionMs

            // If the ID matches a YouTube 11-char ID format, pass as direct videoId.
            // Otherwise pass null so that MusicPlayer automatically finds the best match via search.
            val isYoutubeId = remoteTrack.id.length == 11 && remoteTrack.id.matches(YOUTUBE_ID_REGEX)
            val playable = PlayableTrack(
                title = remoteTrack.title,
                artist = remoteTrack.artist,
                album = remoteTrack.album.orEmpty(),
                artworkUrl = remoteTrack.artworkUrl,
                durationMs = remoteTrack.durationMs ?: 0L,
                videoId = if (isYoutubeId) remoteTrack.id else null,
            )

            musicPlayer.play(
                track = playable,
                sourceLabel = "LastWave Jam",
                startRadio = false,
                startPositionMs = positionMs,
            )

            if (!isPlaying) {
                musicPlayer.pause()
            }
        } else {
            loadingTrackId = null
            quietUntilMs = SystemClock.elapsedRealtime() + 800L
            val currentPos = musicPlayer.progressState.value.positionMs

            if (abs(currentPos - positionMs) > DRIFT_SEEK_THRESHOLD_MS) {
                musicPlayer.seekTo(positionMs)
            }

            if (isPlaying && !musicPlayer.state.value.isPlaying) {
                musicPlayer.resume()
            } else if (!isPlaying && musicPlayer.state.value.isPlaying) {
                musicPlayer.pause()
            }
        }
    }

    private fun isSameTrack(local: PlayableTrack?, remote: TrackInfoPayload): Boolean {
        if (local == null) return false
        if (local.videoId != null && remote.id.isNotBlank() && local.videoId == remote.id) return true
        if (local.title.equals(remote.title, ignoreCase = true)) {
            if (local.artist.isBlank() || remote.artist.isBlank()) return true
            if (local.artist.equals(remote.artist, ignoreCase = true)) return true
        }
        return false
    }

    private data class StateSnapshot(
        val isPlaying: Boolean,
        val current: PlayableTrack?,
    )
}
