package com.lastwave.app.data.jam

import android.util.Log
import com.lastwave.app.data.local.SettingsPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface JamSessionState {
    data object Disconnected : JamSessionState
    data class Connecting(val roomCode: String? = null) : JamSessionState
    data class Active(
        val roomCode: String,
        val userId: String,
        val displayName: String,
        val isHost: Boolean,
        val roomMode: String,
        val participants: List<ParticipantPayload>,
        val currentTrack: TrackInfoPayload? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
    ) : JamSessionState
    data class Error(val message: String) : JamSessionState
}

sealed interface RemotePlaybackEvent {
    data class Play(val positionMs: Long, val serverTimeMs: Long) : RemotePlaybackEvent
    data class Pause(val positionMs: Long) : RemotePlaybackEvent
    data class Seek(val positionMs: Long) : RemotePlaybackEvent
    data class ChangeTrack(val track: TrackInfoPayload, val isPlaying: Boolean, val positionMs: Long) : RemotePlaybackEvent
    data class FullSync(val playback: PlaybackStatePayload) : RemotePlaybackEvent
}

@Singleton
class JamRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsPreferences: SettingsPreferences,
) {
    companion object {
        private const val TAG = "LastWaveJam"
        // Default production sync daemon address
        const val DEFAULT_JAM_SERVER_WS = "wss://jam.clashprojects.qd.je/ws"
        const val DEFAULT_JAM_SERVER_HTTP = "https://jam.clashprojects.qd.je"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _sessionState = MutableStateFlow<JamSessionState>(JamSessionState.Disconnected)
    val sessionState: StateFlow<JamSessionState> = _sessionState.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessagePayload>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessagePayload>> = _chatMessages.asStateFlow()

    private val _remotePlaybackEvents = MutableSharedFlow<RemotePlaybackEvent>(extraBufferCapacity = 64)
    val remotePlaybackEvents: SharedFlow<RemotePlaybackEvent> = _remotePlaybackEvents.asSharedFlow()

    private var activeWebSocket: WebSocket? = null
    private var pendingAction: (() -> Unit)? = null

    var serverUrl: String = DEFAULT_JAM_SERVER_WS

    init {
        scope.launch {
            settingsPreferences.settings.collect { settings ->
                serverUrl = settings.jamServerUrl.ifBlank { DEFAULT_JAM_SERVER_WS }
            }
        }
    }

    fun createRoom(displayName: String, roomMode: String = JamProtocolConstants.MODE_COLLABORATIVE) {
        val trimmed = displayName.trim().takeIf { it.isNotEmpty() } ?: "Listener"
        connectAndExecute {
            val payload = json.encodeToJsonElement(CreateRoomRequest(name = trimmed, roomMode = roomMode))
            sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_CREATE_ROOM, payload = payload))
        }
    }

    fun joinRoom(roomCode: String, displayName: String) {
        val code = roomCode.trim().uppercase()
        val name = displayName.trim().takeIf { it.isNotEmpty() } ?: "Listener"
        if (code.isEmpty()) {
            _sessionState.value = JamSessionState.Error("Room code is required")
            return
        }

        connectAndExecute(code) {
            val payload = json.encodeToJsonElement(JoinRoomRequest(roomCode = code, name = name))
            sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_JOIN_ROOM, payload = payload))
        }
    }

    fun leaveRoom() {
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_LEAVE_ROOM))
        disconnect()
    }

    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val payload = json.encodeToJsonElement(SendChatRequest(text = trimmed))
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_SEND_CHAT, payload = payload))
    }

    fun broadcastPlay(positionMs: Long) {
        val payload = json.encodeToJsonElement(
            PlaybackActionPayload(
                action = JamProtocolConstants.ACTION_PLAY,
                positionMs = positionMs,
                clientTimeMs = System.currentTimeMillis(),
                isPlaying = true,
            )
        )
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_PLAYBACK_ACTION, payload = payload))
    }

    fun broadcastPause(positionMs: Long) {
        val payload = json.encodeToJsonElement(
            PlaybackActionPayload(
                action = JamProtocolConstants.ACTION_PAUSE,
                positionMs = positionMs,
                clientTimeMs = System.currentTimeMillis(),
                isPlaying = false,
            )
        )
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_PLAYBACK_ACTION, payload = payload))
    }

    fun broadcastSeek(positionMs: Long) {
        val payload = json.encodeToJsonElement(
            PlaybackActionPayload(
                action = JamProtocolConstants.ACTION_SEEK,
                positionMs = positionMs,
                clientTimeMs = System.currentTimeMillis(),
            )
        )
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_PLAYBACK_ACTION, payload = payload))
    }

    fun broadcastChangeTrack(track: TrackInfoPayload, positionMs: Long = 0L, isPlaying: Boolean = true) {
        val payload = json.encodeToJsonElement(
            PlaybackActionPayload(
                action = JamProtocolConstants.ACTION_CHANGE_TRACK,
                track = track,
                positionMs = positionMs,
                clientTimeMs = System.currentTimeMillis(),
                isPlaying = isPlaying,
            )
        )
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_PLAYBACK_ACTION, payload = payload))
    }

    fun changeRoomMode(roomMode: String) {
        val current = _sessionState.value
        if (current is JamSessionState.Active && current.isHost) {
            val payload = json.encodeToJsonElement(ChangeRoomModeRequest(roomMode = roomMode))
            sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_CHANGE_ROOM_MODE, payload = payload))
            _sessionState.value = current.copy(roomMode = roomMode)
        }
    }

    private var pingJob: Job? = null

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(25_000L)
                sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_PING))
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun connectAndExecute(targetCode: String? = null, action: () -> Unit) {
        disconnect()
        _sessionState.value = JamSessionState.Connecting(targetCode)
        pendingAction = action

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Jam WebSocket connected to $serverUrl")
                startPingLoop()
                pendingAction?.invoke()
                pendingAction = null
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Jam WebSocket closed: $code / $reason")
                _sessionState.value = JamSessionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Jam WebSocket error: ${t.message}", t)
                _sessionState.value = JamSessionState.Error(t.localizedMessage ?: "Connection failed")
            }
        })
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val envelope = json.decodeFromString<JamEnvelope>(text)
            when (envelope.type) {
                JamProtocolConstants.MSG_ROOM_CREATED -> {
                    val payload = envelope.payload?.let { json.decodeFromJsonElement<RoomCreatedPayload>(it) }
                    if (payload != null) {
                        val hostParticipant = ParticipantPayload(
                            userId = payload.userId,
                            name = payload.name.ifEmpty { "Host" },
                            isHost = true,
                            joinedAt = System.currentTimeMillis(),
                        )
                        _sessionState.value = JamSessionState.Active(
                            roomCode = payload.roomCode,
                            userId = payload.userId,
                            displayName = payload.name.ifEmpty { "Host" },
                            isHost = true,
                            roomMode = payload.roomMode,
                            participants = listOf(hostParticipant),
                            currentTrack = null,
                            isPlaying = false,
                            positionMs = 0L,
                        )
                    }
                }

                JamProtocolConstants.MSG_SYNC_STATE -> {
                    val payload = envelope.payload?.let { json.decodeFromJsonElement<SyncStatePayload>(it) }
                    if (payload != null) {
                        _sessionState.value = JamSessionState.Active(
                            roomCode = payload.roomCode,
                            userId = payload.userId,
                            displayName = payload.name,
                            isHost = payload.isHost,
                            roomMode = payload.roomMode,
                            participants = payload.participants,
                            currentTrack = payload.playback.currentTrack,
                            isPlaying = payload.playback.isPlaying,
                            positionMs = payload.playback.positionMs,
                        )
                        _chatMessages.value = payload.chatHistory
                        scope.launch {
                            _remotePlaybackEvents.emit(RemotePlaybackEvent.FullSync(payload.playback))
                        }
                    }
                }

                JamProtocolConstants.MSG_USER_JOINED -> {
                    val current = _sessionState.value
                    if (current is JamSessionState.Active && envelope.payload != null) {
                        val participant = json.decodeFromJsonElement<ParticipantPayload>(envelope.payload)
                        val updatedList = current.participants.filter { it.userId != participant.userId } + participant
                        _sessionState.value = current.copy(participants = updatedList)
                    }
                }

                JamProtocolConstants.MSG_USER_LEFT -> {
                    val current = _sessionState.value
                    if (current is JamSessionState.Active && envelope.payload != null) {
                        val userLeft = json.decodeFromJsonElement<UserLeftPayload>(envelope.payload)
                        val updatedList = current.participants.filter { it.userId != userLeft.userId }
                        _sessionState.value = current.copy(participants = updatedList)
                    }
                }

                JamProtocolConstants.MSG_HOST_CHANGED -> {
                    val current = _sessionState.value
                    if (current is JamSessionState.Active && envelope.payload != null) {
                        val hostChanged = json.decodeFromJsonElement<HostChangedPayload>(envelope.payload)
                        val newHostId = hostChanged.effectiveHostId
                        val updatedList = current.participants.map {
                            if (it.userId == newHostId) it.copy(isHost = true) else it.copy(isHost = false)
                        }
                        val isHostNow = (newHostId == current.userId)
                        _sessionState.value = current.copy(participants = updatedList, isHost = isHostNow)
                    }
                }

                JamProtocolConstants.MSG_ROOM_MODE_CHANGED -> {
                    val current = _sessionState.value
                    if (current is JamSessionState.Active && envelope.payload != null) {
                        val modeChanged = json.decodeFromJsonElement<RoomModeChangedPayload>(envelope.payload)
                        _sessionState.value = current.copy(roomMode = modeChanged.roomMode)
                    }
                }

                JamProtocolConstants.MSG_CHAT_MESSAGE -> {
                    val msg = envelope.payload?.let { json.decodeFromJsonElement<ChatMessagePayload>(it) }
                    if (msg != null) {
                        _chatMessages.value = (_chatMessages.value + msg).takeLast(50)
                    }
                }

                JamProtocolConstants.MSG_SYNC_PLAYBACK -> {
                    val action = envelope.payload?.let { json.decodeFromJsonElement<PlaybackActionPayload>(it) }
                    if (action != null) {
                        applyPlaybackAction(action)
                    }
                }

                JamProtocolConstants.MSG_ERROR -> {
                    val err = envelope.payload?.let { json.decodeFromJsonElement<ErrorPayload>(it) }
                    val message = err?.message ?: "Unknown server error"
                    Log.w(TAG, "Server error: $message")
                    _sessionState.value = JamSessionState.Error(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    private fun applyPlaybackAction(action: PlaybackActionPayload) {
        val current = _sessionState.value
        if (current is JamSessionState.Active) {
            _sessionState.value = current.copy(
                isPlaying = action.isPlaying,
                positionMs = action.positionMs,
                currentTrack = action.track ?: current.currentTrack,
            )
        }

        scope.launch {
            when (action.action) {
                JamProtocolConstants.ACTION_PLAY -> {
                    _remotePlaybackEvents.emit(RemotePlaybackEvent.Play(action.positionMs, action.serverTimeMs))
                }
                JamProtocolConstants.ACTION_PAUSE -> {
                    _remotePlaybackEvents.emit(RemotePlaybackEvent.Pause(action.positionMs))
                }
                JamProtocolConstants.ACTION_SEEK -> {
                    _remotePlaybackEvents.emit(RemotePlaybackEvent.Seek(action.positionMs))
                }
                JamProtocolConstants.ACTION_CHANGE_TRACK -> {
                    action.track?.let { track ->
                        _remotePlaybackEvents.emit(RemotePlaybackEvent.ChangeTrack(track, action.isPlaying, action.positionMs))
                    }
                }
            }
        }
    }

    private fun sendEnvelope(envelope: JamEnvelope) {
        try {
            val text = json.encodeToString(envelope)
            activeWebSocket?.send(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send envelope: ${envelope.type}", e)
        }
    }

    private fun disconnect() {
        stopPingLoop()
        activeWebSocket?.close(1000, "Normal closure")
        activeWebSocket = null
        _sessionState.value = JamSessionState.Disconnected
        _chatMessages.value = emptyList()
    }
}
