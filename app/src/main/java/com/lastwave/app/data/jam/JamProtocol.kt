package com.lastwave.app.data.jam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object JamProtocolConstants {
    const val MSG_CREATE_ROOM = "create_room"
    const val MSG_JOIN_ROOM = "join_room"
    const val MSG_LEAVE_ROOM = "leave_room"
    const val MSG_SEND_CHAT = "send_chat"
    const val MSG_PLAYBACK_ACTION = "playback_action"
    const val MSG_PING = "ping"

    const val MSG_ROOM_CREATED = "room_created"
    const val MSG_SYNC_STATE = "sync_state"
    const val MSG_USER_JOINED = "user_joined"
    const val MSG_USER_LEFT = "user_left"
    const val MSG_CHAT_MESSAGE = "chat_message"
    const val MSG_SYNC_PLAYBACK = "sync_playback"
    const val MSG_HOST_CHANGED = "host_changed"
    const val MSG_CHANGE_ROOM_MODE = "change_room_mode"
    const val MSG_ROOM_MODE_CHANGED = "room_mode_changed"
    const val MSG_PONG = "pong"
    const val MSG_ERROR = "error"

    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_SEEK = "seek"
    const val ACTION_SKIP_NEXT = "skip_next"
    const val ACTION_CHANGE_TRACK = "change_track"
    const val ACTION_QUEUE_ADD = "queue_add"

    const val MODE_HOST_ONLY = "host_only"
    const val MODE_COLLABORATIVE = "collaborative"
}

@Serializable
data class JamEnvelope(
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: JsonElement? = null,
)

@Serializable
data class CreateRoomRequest(
    @SerialName("name") val name: String,
    @SerialName("room_mode") val roomMode: String = JamProtocolConstants.MODE_COLLABORATIVE,
)

@Serializable
data class JoinRoomRequest(
    @SerialName("room_code") val roomCode: String,
    @SerialName("name") val name: String,
)

@Serializable
data class SendChatRequest(
    @SerialName("text") val text: String,
)

@Serializable
data class RoomCreatedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = true,
    @SerialName("room_mode") val roomMode: String = JamProtocolConstants.MODE_COLLABORATIVE,
)

@Serializable
data class ParticipantPayload(
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("joined_at") val joinedAt: Long = 0L,
)

@Serializable
data class HostChangedPayload(
    @SerialName("new_host_id") val newHostId: String = "",
    @SerialName("new_host_name") val newHostName: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
) {
    val effectiveHostId: String get() = newHostId.ifEmpty { userId }
    val effectiveHostName: String get() = newHostName.ifEmpty { name }
}

@Serializable
data class UserLeftPayload(
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("total_users") val totalUsers: Int = 0,
)

@Serializable
data class ChangeRoomModeRequest(
    @SerialName("room_mode") val roomMode: String,
)

@Serializable
data class RoomModeChangedPayload(
    @SerialName("room_mode") val roomMode: String = JamProtocolConstants.MODE_COLLABORATIVE,
    @SerialName("changed_by") val changedBy: String = "",
)

@Serializable
data class ChatMessagePayload(
    @SerialName("id") val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("text") val text: String = "",
    @SerialName("timestamp") val timestamp: Long = 0L,
)

@Serializable
data class TrackInfoPayload(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("artist") val artist: String,
    @SerialName("album") val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("suggested_by") val suggestedBy: String? = null,
)

@Serializable
data class PlaybackActionPayload(
    @SerialName("action") val action: String,
    @SerialName("track") val track: TrackInfoPayload? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("client_time_ms") val clientTimeMs: Long? = null,
    @SerialName("server_time_ms") val serverTimeMs: Long = 0L,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("queue") val queue: List<TrackInfoPayload>? = null,
    @SerialName("revision") val revision: Long = 0L,
)

@Serializable
data class PlaybackStatePayload(
    @SerialName("current_track") val currentTrack: TrackInfoPayload? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("server_time_ms") val serverTimeMs: Long = 0L,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("queue") val queue: List<TrackInfoPayload> = emptyList(),
    @SerialName("revision") val revision: Long = 0L,
)

@Serializable
data class SyncStatePayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    @SerialName("name") val name: String,
    @SerialName("is_host") val isHost: Boolean,
    @SerialName("room_mode") val roomMode: String = JamProtocolConstants.MODE_COLLABORATIVE,
    @SerialName("playback") val playback: PlaybackStatePayload,
    @SerialName("participants") val participants: List<ParticipantPayload> = emptyList(),
    @SerialName("chat_history") val chatHistory: List<ChatMessagePayload> = emptyList(),
)

@Serializable
data class ErrorPayload(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String = "",
)
