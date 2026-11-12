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
import kotlinx.coroutines.withContext
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
        /** True for the main host and the co-host. */
        val isHost: Boolean,
        val roomMode: String,
        val participants: List<ParticipantPayload>,
        val currentTrack: TrackInfoPayload? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val hostId: String = "",
        val coHostId: String = "",
        val permanent: Boolean = false,
        /** Friendly title of an always-on room ("India"); empty otherwise. */
        val roomName: String = "",
        /** Always-on rooms are 24/7 radio stations driven by the server. */
        val radio: Boolean = false,
        /** Station on auto radio (true) or run by a station admin (false). */
        val stationAuto: Boolean = true,
        /** The main host's upcoming queue, shared read-only. */
        val queue: List<TrackInfoPayload> = emptyList(),
    ) : JamSessionState {
        /** Only the main host may give and remove co-hosts. */
        val isMainHost: Boolean get() = userId.isNotEmpty() && userId == hostId
    }
    data class Error(val message: String) : JamSessionState
}

/** A rejected request while inside a room (rate limit, host rules). */
data class JamNotice(
    val message: String,
    /** Wall-clock ms when the playback cooldown ends; 0 when not a cooldown. */
    val retryAtMs: Long = 0L,
)

sealed interface RemotePlaybackEvent {
    val fromUserId: String?
    data class Play(val positionMs: Long, val serverTimeMs: Long, override val fromUserId: String? = null) : RemotePlaybackEvent
    data class Pause(val positionMs: Long, override val fromUserId: String? = null) : RemotePlaybackEvent
    data class Seek(val positionMs: Long, override val fromUserId: String? = null) : RemotePlaybackEvent
    data class ChangeTrack(val track: TrackInfoPayload, val isPlaying: Boolean, val positionMs: Long, override val fromUserId: String? = null) : RemotePlaybackEvent
    data class FullSync(val playback: PlaybackStatePayload) : RemotePlaybackEvent {
        override val fromUserId: String? = null
    }
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

    private val _cooldownUntilMs = MutableStateFlow(0L)
    /** Wall-clock ms until which pause/skip are locked for you; 0 when free. */
    val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()
    private var cooldownJob: Job? = null

    private fun startCooldown(untilMs: Long) {
        val wait = untilMs - System.currentTimeMillis()
        if (wait <= 0L) return
        _cooldownUntilMs.value = untilMs
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            delay(wait + 100L)
            if (_cooldownUntilMs.value == untilMs) _cooldownUntilMs.value = 0L
        }
    }

    /**
     * Live listener counts for the always-on rooms ({code: count}). Empty on
     * any failure, so the join screen just omits the counts.
     */
    suspend fun fetchRoomCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        try {
            val httpUrl = serverUrl
                .replaceFirst("wss://", "https://")
                .replaceFirst("ws://", "http://")
                .substringBefore("/ws")
                .trimEnd('/') + "/rooms"
            val request = Request.Builder().url(httpUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyMap<String, Int>()
                val body = response.body?.string().orEmpty()
                json.decodeFromString<RoomsResponse>(body).rooms.associate { it.code to it.participants }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private val _notice = MutableStateFlow<JamNotice?>(null)
    val notice: StateFlow<JamNotice?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

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

    /**
     * Shareable https link. Opens the web invite page, which launches whichever
     * LastWave is installed and previews with the room code in chat apps.
     */
    fun inviteLink(code: String): String {
        val base = serverUrl
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .substringBefore("/ws")
            .trimEnd('/')
        return "$base/?code=$code"
    }

    fun createRoom(displayName: String, roomMode: String = JamProtocolConstants.MODE_COLLABORATIVE) {
        val trimmed = displayName.trim().takeIf { it.isNotEmpty() } ?: "Listener"
        connectAndExecute {
            val payload = json.encodeToJsonElement(CreateRoomRequest(name = trimmed, roomMode = roomMode))
            sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_CREATE_ROOM, payload = payload))
        }
    }

    fun joinRoom(roomCode: String, displayName: String) {
        val code = parseJamCode(roomCode)
        val name = displayName.trim().takeIf { it.isNotEmpty() } ?: "Listener"
        if (code.isEmpty()) {
            _sessionState.value = JamSessionState.Error("Room code is required")
            return
        }
        if (JamStations.any { it.code == code } &&
            displayName.trim().lowercase() in setOf("", "listener", "host", "guest", "user", "anonymous")
        ) {
            _sessionState.value = JamSessionState.Error("Pick your own display name to join this public room")
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

    fun sendChat(text: String, replyToId: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val payload = json.encodeToJsonElement(SendChatRequest(text = trimmed, replyToId = replyToId))
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_SEND_CHAT, payload = payload))
    }

    /** Main host only: publish the upcoming queue so listeners can see it. */
    fun publishQueue(queue: List<TrackInfoPayload>) {
        if (_sessionState.value !is JamSessionState.Active) return
        val payload = json.encodeToJsonElement(QueueUpdateRequest(queue = queue.take(5)))
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_QUEUE_UPDATE, payload = payload))
    }

    /** Station admin only: hand the live station to [userId]. */
    fun transferAdmin(userId: String) {
        val payload = json.encodeToJsonElement(GrantHostRequest(userId = userId))
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_TRANSFER_HOST, payload = payload))
    }

    /** Main host only: make [userId] a co-host (a room has at most two hosts). */
    fun grantHost(userId: String) {
        val payload = json.encodeToJsonElement(GrantHostRequest(userId = userId))
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_GRANT_HOST, payload = payload))
    }

    /** Main host only: remove [userId]'s co-host role. */
    fun revokeHost(userId: String) {
        val payload = json.encodeToJsonElement(GrantHostRequest(userId = userId))
        sendEnvelope(JamEnvelope(type = JamProtocolConstants.MSG_REVOKE_HOST, payload = payload))
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
                stopPingLoop()
                activeWebSocket = null
                _sessionState.value = JamSessionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Jam WebSocket error: ${t.message}", t)
                stopPingLoop()
                activeWebSocket = null
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
                            hostId = payload.userId,
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
                            hostId = payload.hostId,
                            coHostId = payload.coHostId,
                            permanent = payload.permanent,
                            roomName = payload.roomName,
                            radio = payload.radio,
                            stationAuto = payload.stationAuto,
                            queue = payload.playback.queue,
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
                        applyRoles(current, hostChanged.effectiveHostId, hostChanged.coHostId)
                    }
                }

                JamProtocolConstants.MSG_STATION_MODE -> {
                    val current = _sessionState.value
                    val mode = envelope.payload?.let { json.decodeFromJsonElement<StationModePayload>(it) }
                    if (current is JamSessionState.Active && mode != null) {
                        _sessionState.value = current.copy(stationAuto = mode.auto)
                    }
                }

                JamProtocolConstants.MSG_COOLDOWN -> {
                    val cd = envelope.payload?.let { json.decodeFromJsonElement<CooldownPayload>(it) }
                    if (cd != null) startCooldown(cd.retryAtMs)
                }

                JamProtocolConstants.MSG_QUEUE_UPDATED -> {
                    val current = _sessionState.value
                    if (current is JamSessionState.Active && envelope.payload != null) {
                        val updated = json.decodeFromJsonElement<QueueUpdatedPayload>(envelope.payload)
                        _sessionState.value = current.copy(queue = updated.queue)
                    }
                }

                JamProtocolConstants.MSG_COHOST_CHANGED -> {
                    val current = _sessionState.value
                    if (current is JamSessionState.Active && envelope.payload != null) {
                        val changed = json.decodeFromJsonElement<CoHostChangedPayload>(envelope.payload)
                        applyRoles(current, changed.hostId.ifEmpty { current.hostId }, changed.coHostId)
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
                    if (_sessionState.value is JamSessionState.Active) {
                        // A rejected request inside a room must not eject you from it.
                        val limited = err != null && err.code == "rate_limited"
                        val retryAt = when {
                            !limited -> 0L
                            err!!.retryAtMs > 0L -> err.retryAtMs
                            else -> System.currentTimeMillis() + err.retryAfterMs
                        }
                        _notice.value = JamNotice(message, retryAt)
                        if (retryAt > 0L) startCooldown(retryAt)
                    } else {
                        _sessionState.value = JamSessionState.Error(message)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    private fun applyRoles(current: JamSessionState.Active, hostId: String, coHostId: String) {
        val updated = current.participants.map {
            it.copy(
                isHost = it.userId == hostId || it.userId == coHostId,
                isCoHost = coHostId.isNotEmpty() && it.userId == coHostId,
            )
        }
        _sessionState.value = current.copy(
            participants = updated,
            hostId = hostId,
            coHostId = coHostId,
            isHost = current.userId == hostId || current.userId == coHostId,
        )
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
                    _remotePlaybackEvents.emit(RemotePlaybackEvent.Play(action.positionMs, action.serverTimeMs, action.fromUserId))
                }
                JamProtocolConstants.ACTION_PAUSE -> {
                    _remotePlaybackEvents.emit(RemotePlaybackEvent.Pause(action.positionMs, action.fromUserId))
                }
                JamProtocolConstants.ACTION_SEEK -> {
                    _remotePlaybackEvents.emit(RemotePlaybackEvent.Seek(action.positionMs, action.fromUserId))
                }
                JamProtocolConstants.ACTION_CHANGE_TRACK -> {
                    action.track?.let { track ->
                        _remotePlaybackEvents.emit(RemotePlaybackEvent.ChangeTrack(track, action.isPlaying, action.positionMs, action.fromUserId))
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
        _notice.value = null
        _cooldownUntilMs.value = 0L
        cooldownJob?.cancel()
    }
}
