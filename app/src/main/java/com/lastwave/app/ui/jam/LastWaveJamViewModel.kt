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
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
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
    val isMainHost: Boolean = false,
    val coHostId: String = "",
    val isPermanentRoom: Boolean = false,
    val roomName: String = "",
    val isRadio: Boolean = false,
    val stationAuto: Boolean = true,
    val queue: List<TrackInfoPayload> = emptyList(),
    val myUserId: String = "",
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val roomMode: String = JamProtocolConstants.MODE_COLLABORATIVE,
    val participants: List<ParticipantPayload> = emptyList(),
    val errorMessage: String? = null,
    val pendingInputCode: String = "",
    val pendingDisplayName: String = "",
    val currentTrackTitle: String = "",
    val currentTrackArtist: String = "",
    val currentArtworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val serverUrl: String = JamRepository.DEFAULT_JAM_SERVER_WS,
)

@HiltViewModel
class LastWaveJamViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jamRepository: JamRepository,
    private val musicPlayer: MusicPlayer,
    private val settingsPreferences: SettingsPreferences,
    private val jamPlaybackSync: com.lastwave.app.playback.jam.JamPlaybackSync,
) : ViewModel() {

    private val _pendingRoomCode = MutableStateFlow("")
    val pendingRoomCode: StateFlow<String> = _pendingRoomCode.asStateFlow()

    private val _pendingDisplayName = MutableStateFlow("Listener")
    val pendingDisplayName: StateFlow<String> = _pendingDisplayName.asStateFlow()

    private val _pendingRoomMode = MutableStateFlow(JamProtocolConstants.MODE_COLLABORATIVE)
    val pendingRoomMode: StateFlow<String> = _pendingRoomMode.asStateFlow()

    val chatMessages: StateFlow<List<ChatMessagePayload>> = jamRepository.chatMessages

    /** Server rejections shown inside a room (cooldown, host rules). */
    val notice: StateFlow<com.lastwave.app.data.jam.JamNotice?> = jamRepository.notice

    fun clearNotice() = jamRepository.clearNotice()

    /** Wall-clock ms until which pause/skip are locked in a public room; 0 = free. */
    val cooldownUntilMs: StateFlow<Long> = jamRepository.cooldownUntilMs

    private val _roomCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Live listener counts for the always-on rooms, keyed by room code. */
    val roomCounts: StateFlow<Map<String, Int>> = _roomCounts.asStateFlow()

    fun refreshRoomCounts() {
        viewModelScope.launch {
            val counts = jamRepository.fetchRoomCounts()
            if (counts.isNotEmpty()) _roomCounts.value = counts
        }
    }

    fun grantHost(userId: String) = jamRepository.grantHost(userId)

    fun revokeHost(userId: String) = jamRepository.revokeHost(userId)

    /** Live stations: the admin hands the station to someone else. */
    fun makeAdmin(userId: String) = jamRepository.transferAdmin(userId)

    val uiState: StateFlow<JamUiState> = combine(
        combine(jamRepository.sessionState, _pendingRoomCode, _pendingDisplayName, _pendingRoomMode) { session, code, name, mode ->
            SessionInput(session, code, name, mode)
        },
        combine(musicPlayer.state, musicPlayer.progressState, settingsPreferences.settings) { playerState, progress, settings ->
            PlayerAndSettings(playerState, progress, settings.jamServerUrl.ifBlank { JamRepository.DEFAULT_JAM_SERVER_WS })
        }
    ) { input, extra ->
        val session = input.session
        val code = input.code
        val name = input.name
        val mode = input.mode
        val playerState = extra.playerState
        val progress = extra.progress
        val currentTrack = playerState.current
        val activeSession = session as? JamSessionState.Active
        val title = currentTrack?.title ?: activeSession?.currentTrack?.title.orEmpty()
        val artist = currentTrack?.artist ?: activeSession?.currentTrack?.artist.orEmpty()
        val artworkUrl = currentTrack?.artworkUrl ?: activeSession?.currentTrack?.artworkUrl
        val isPlaying = playerState.isPlaying
        val pos = progress.positionMs
        val dur = progress.durationMs

        when (session) {
            is JamSessionState.Active -> JamUiState(
                sessionState = session,
                roomCode = session.roomCode,
                isHost = session.isHost,
                isMainHost = session.isMainHost,
                coHostId = session.coHostId,
                isPermanentRoom = session.permanent,
                roomName = session.roomName,
                isRadio = session.radio,
                stationAuto = session.stationAuto,
                queue = session.queue,
                myUserId = session.userId,
                isConnected = true,
                isConnecting = false,
                roomMode = session.roomMode,
                participants = session.participants,
                pendingInputCode = session.roomCode,
                pendingDisplayName = session.displayName,
                currentTrackTitle = title,
                currentTrackArtist = artist,
                currentArtworkUrl = artworkUrl,
                isPlaying = isPlaying,
                currentPositionMs = pos,
                durationMs = dur,
                serverUrl = extra.serverUrl,
            )
            is JamSessionState.Connecting -> JamUiState(
                sessionState = session,
                roomCode = session.roomCode.orEmpty(),
                isConnecting = true,
                roomMode = mode,
                pendingInputCode = code,
                pendingDisplayName = name,
                currentTrackTitle = title,
                currentTrackArtist = artist,
                currentArtworkUrl = artworkUrl,
                isPlaying = isPlaying,
                currentPositionMs = pos,
                durationMs = dur,
                serverUrl = extra.serverUrl,
            )
            is JamSessionState.Error -> JamUiState(
                sessionState = session,
                errorMessage = session.message,
                roomMode = mode,
                pendingInputCode = code,
                pendingDisplayName = name,
                currentTrackTitle = title,
                currentTrackArtist = artist,
                currentArtworkUrl = artworkUrl,
                isPlaying = isPlaying,
                currentPositionMs = pos,
                durationMs = dur,
                serverUrl = extra.serverUrl,
            )
            JamSessionState.Disconnected -> JamUiState(
                sessionState = session,
                roomMode = mode,
                pendingInputCode = code,
                pendingDisplayName = name,
                currentTrackTitle = title,
                currentTrackArtist = artist,
                currentArtworkUrl = artworkUrl,
                isPlaying = isPlaying,
                currentPositionMs = pos,
                durationMs = dur,
                serverUrl = extra.serverUrl,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), JamUiState())

    private data class SessionInput(
        val session: JamSessionState,
        val code: String,
        val name: String,
        val mode: String,
    )

    private data class PlayerAndSettings(
        val playerState: MusicPlayerState,
        val progress: PlaybackProgressState,
        val serverUrl: String,
    )

    init {
        // Hydrate saved preferences
        viewModelScope.launch {
            settingsPreferences.settings.collect { settings ->
                if (_pendingDisplayName.value == "Listener" && settings.jamDisplayName.isNotBlank()) {
                    _pendingDisplayName.value = settings.jamDisplayName
                }
                _pendingRoomMode.value = settings.jamRoomMode
            }
        }
    }

    fun setPendingCode(code: String) {
        _pendingRoomCode.value = code.trim().uppercase()
    }

    fun setPendingDisplayName(name: String) {
        _pendingDisplayName.value = name
        viewModelScope.launch {
            settingsPreferences.setJamDisplayName(name)
        }
    }

    fun setPendingRoomMode(mode: String) {
        _pendingRoomMode.value = mode
        viewModelScope.launch {
            settingsPreferences.setJamRoomMode(mode)
        }
    }

    fun changeActiveRoomMode(mode: String) {
        _pendingRoomMode.value = mode
        jamRepository.changeRoomMode(mode)
        viewModelScope.launch {
            settingsPreferences.setJamRoomMode(mode)
        }
    }

    fun setCustomServerUrl(url: String) {
        viewModelScope.launch {
            settingsPreferences.setJamServerUrl(url)
        }
    }

    fun resetServerUrl() {
        viewModelScope.launch {
            settingsPreferences.setJamServerUrl(JamRepository.DEFAULT_JAM_SERVER_WS)
        }
    }

    fun togglePlayback() {
        val isAllowed = !uiState.value.isConnected || uiState.value.isHost || uiState.value.roomMode == JamProtocolConstants.MODE_COLLABORATIVE
        // Resuming is always allowed; only pausing is locked during the cooldown.
        val pauseLocked = uiState.value.isPlaying && cooldownUntilMs.value > 0L
        if (isAllowed && !pauseLocked) {
            musicPlayer.togglePlayPause()
        }
    }

    fun skipNext() {
        val isAllowed = !uiState.value.isConnected || uiState.value.isHost || uiState.value.roomMode == JamProtocolConstants.MODE_COLLABORATIVE
        if (isAllowed && cooldownUntilMs.value == 0L) {
            musicPlayer.next()
        }
    }

    fun skipPrevious() {
        val isAllowed = !uiState.value.isConnected || uiState.value.isHost || uiState.value.roomMode == JamProtocolConstants.MODE_COLLABORATIVE
        if (isAllowed && cooldownUntilMs.value == 0L) {
            musicPlayer.previous()
        }
    }

    fun seekTo(positionMs: Long) {
        val isAllowed = !uiState.value.isConnected || uiState.value.isHost || uiState.value.roomMode == JamProtocolConstants.MODE_COLLABORATIVE
        if (isAllowed && cooldownUntilMs.value == 0L) {
            musicPlayer.seekTo(positionMs)
        }
    }

    fun startJamSession(roomMode: String? = null) {
        val name = _pendingDisplayName.value.ifBlank { "Host" }
        val mode = roomMode ?: _pendingRoomMode.value
        jamRepository.createRoom(displayName = name, roomMode = mode)
    }

    fun joinJamSession(code: String? = null) {
        val targetCode = (code ?: _pendingRoomCode.value).trim().uppercase()
        val name = _pendingDisplayName.value.ifBlank { "Listener" }
        jamRepository.joinRoom(roomCode = targetCode, displayName = name)
    }

    fun leaveSession() {
        jamRepository.leaveRoom()
    }

    fun sendChat(text: String, replyToId: String? = null) {
        jamRepository.sendChat(text, replyToId)
    }

    fun shareInvite(roomCode: String) {
        val inviteLink = jamRepository.inviteLink(roomCode)
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
