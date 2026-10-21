package com.lastwave.app.ui.jam

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jam.ChatMessagePayload
import com.lastwave.app.data.jam.JamProtocolConstants
import com.lastwave.app.data.jam.JamRepository
import com.lastwave.app.data.jam.JamSessionState
import com.lastwave.app.data.jam.ParticipantPayload
import com.lastwave.app.data.jam.RemotePlaybackEvent
import com.lastwave.app.data.jam.TrackInfoPayload
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class JamUiState(
    val sessionState: JamSessionState = JamSessionState.Disconnected,
    val roomCode: String = "",
    val isHost: Boolean = false,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val participants: List<ParticipantPayload> = emptyList(),
    val errorMessage: String? = null,
    val pendingInputCode: String = "",
    val pendingDisplayName: String = "",
    val currentTrackTitle: String = "",
    val currentTrackArtist: String = "",
    val isPlaying: Boolean = false,
)

@HiltViewModel
class LastWaveJamViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jamRepository: JamRepository,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private val _pendingRoomCode = MutableStateFlow("")
    val pendingRoomCode: StateFlow<String> = _pendingRoomCode.asStateFlow()

    private val _pendingDisplayName = MutableStateFlow("Listener")
    val pendingDisplayName: StateFlow<String> = _pendingDisplayName.asStateFlow()

    val chatMessages: StateFlow<List<ChatMessagePayload>> = jamRepository.chatMessages

    // Prevent echoing remote events back to the server
    private var isApplyingRemoteAction = false

    val uiState: StateFlow<JamUiState> = combine(
        jamRepository.sessionState,
        _pendingRoomCode,
        _pendingDisplayName,
        musicPlayer.state,
    ) { session, code, name, playerState ->
        when (session) {
            is JamSessionState.Active -> JamUiState(
                sessionState = session,
                roomCode = session.roomCode,
                isHost = session.isHost,
                isConnected = true,
                isConnecting = false,
                participants = session.participants,
                pendingInputCode = session.roomCode,
                pendingDisplayName = session.displayName,
                currentTrackTitle = playerState.current?.title ?: session.currentTrack?.title.orEmpty(),
                currentTrackArtist = playerState.current?.artist ?: session.currentTrack?.artist.orEmpty(),
                isPlaying = playerState.isPlaying,
            )
            is JamSessionState.Connecting -> JamUiState(
                sessionState = session,
                roomCode = session.roomCode.orEmpty(),
                isConnecting = true,
                pendingInputCode = code,
                pendingDisplayName = name,
            )
            is JamSessionState.Error -> JamUiState(
                sessionState = session,
                errorMessage = session.message,
                pendingInputCode = code,
                pendingDisplayName = name,
            )
            JamSessionState.Disconnected -> JamUiState(
                sessionState = session,
                pendingInputCode = code,
                pendingDisplayName = name,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), JamUiState())

    init {
        // Listen to remote playback events and update local player
        viewModelScope.launch {
            jamRepository.remotePlaybackEvents.collect { event ->
                handleRemotePlaybackEvent(event)
            }
        }

        // Listen to local player changes and broadcast if in an active session
        viewModelScope.launch {
            musicPlayer.state
                .map { it.isPlaying to it.current }
                .distinctUntilChanged()
                .collect { (isPlaying, current) ->
                    if (isApplyingRemoteAction) return@collect
                    val session = jamRepository.sessionState.value
                    if (session is JamSessionState.Active && (session.isHost || session.roomMode == JamProtocolConstants.MODE_COLLABORATIVE)) {
                        val pos = musicPlayer.progressState.value.positionMs
                        if (current != null) {
                            val trackPayload = TrackInfoPayload(
                                id = current.videoId ?: current.title,
                                title = current.title,
                                artist = current.artist,
                                album = current.album,
                                artworkUrl = current.artworkUrl,
                                durationMs = current.durationMs,
                            )
                            jamRepository.broadcastChangeTrack(trackPayload, pos, isPlaying)
                        } else if (isPlaying) {
                            jamRepository.broadcastPlay(pos)
                        } else {
                            jamRepository.broadcastPause(pos)
                        }
                    }
                }
        }
    }

    private fun handleRemotePlaybackEvent(event: RemotePlaybackEvent) {
        isApplyingRemoteAction = true
        try {
            when (event) {
                is RemotePlaybackEvent.Play -> {
                    musicPlayer.seekTo(event.positionMs)
                    val curr = musicPlayer.state.value.current
                    if (curr != null && !musicPlayer.state.value.isPlaying) {
                        musicPlayer.resume()
                    }
                }
                is RemotePlaybackEvent.Pause -> {
                    musicPlayer.seekTo(event.positionMs)
                    musicPlayer.pause()
                }
                is RemotePlaybackEvent.Seek -> {
                    musicPlayer.seekTo(event.positionMs)
                }
                is RemotePlaybackEvent.ChangeTrack -> {
                    val localCurrent = musicPlayer.state.value.current
                    val sameSong = localCurrent != null && (localCurrent.videoId == event.track.id || localCurrent.title.equals(event.track.title, ignoreCase = true))
                    if (!sameSong) {
                        val playable = PlayableTrack(
                            title = event.track.title,
                            artist = event.track.artist,
                            album = event.track.album.orEmpty(),
                            artworkUrl = event.track.artworkUrl,
                            durationMs = event.track.durationMs ?: 0L,
                            videoId = event.track.id,
                        )
                        musicPlayer.play(playable, sourceLabel = "LastWave Jam")
                        if (event.positionMs > 0) {
                            musicPlayer.seekTo(event.positionMs)
                        }
                    }
                }
                is RemotePlaybackEvent.FullSync -> {
                    event.playback.currentTrack?.let { remoteTrack ->
                        val localCurrent = musicPlayer.state.value.current
                        if (localCurrent == null || localCurrent.videoId != remoteTrack.id) {
                            val playable = PlayableTrack(
                                title = remoteTrack.title,
                                artist = remoteTrack.artist,
                                album = remoteTrack.album.orEmpty(),
                                artworkUrl = remoteTrack.artworkUrl,
                                durationMs = remoteTrack.durationMs ?: 0L,
                                videoId = remoteTrack.id,
                            )
                            musicPlayer.play(playable, sourceLabel = "LastWave Jam")
                        }
                        musicPlayer.seekTo(event.playback.positionMs)
                        if (!event.playback.isPlaying) {
                            musicPlayer.pause()
                        }
                    }
                }
            }
        } finally {
            isApplyingRemoteAction = false
        }
    }

    fun setPendingCode(code: String) {
        _pendingRoomCode.value = code.trim().uppercase()
    }

    fun setPendingDisplayName(name: String) {
        _pendingDisplayName.value = name
    }

    fun startJamSession() {
        val name = _pendingDisplayName.value.ifBlank { "Host" }
        jamRepository.createRoom(displayName = name)
    }

    fun joinJamSession(code: String? = null) {
        val targetCode = code ?: _pendingRoomCode.value
        val name = _pendingDisplayName.value.ifBlank { "Listener" }
        jamRepository.joinRoom(roomCode = targetCode, displayName = name)
    }

    fun leaveSession() {
        jamRepository.leaveRoom()
    }

    fun sendChat(text: String) {
        jamRepository.sendChat(text)
    }

    fun shareInvite(roomCode: String) {
        val inviteLink = "lastwave://jam?code=$roomCode"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Join my LastWave Jam session")
            putExtra(Intent.EXTRA_TEXT, "Join my LastWave Jam session! Room code: $roomCode\n$inviteLink")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "Share LastWave Jam Invite").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
