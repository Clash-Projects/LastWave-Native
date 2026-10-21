# LastWave Sync Server Design Spec

**Date:** 2026-10-21  
**Status:** Approved  
**Component:** `lastwave-sync`  
**Language:** Go (1.22+)

---

## 1. Overview & Objective

LastWave Sync is a high-performance, low-latency "Listen Together" backend service designed for the LastWave music ecosystem (Android & Desktop). It enables users to create listening rooms, synchronize playback (play, pause, seek, track changes, queue updates) in real time with millisecond precision, and chat with each other directly inside the room.

### Key Requirements
1. **Frictionless Name-Gated Entry:** Joining a room requires a 6-character room code and a display name. No complex signups or pre-registration.
2. **In-Room Live Text Chat:** All participants can exchange real-time messages within the room. Late joiners receive the recent chat history buffer (up to 50 messages).
3. **High-Precision Playback Sync:** Millisecond-accurate timestamping and transit latency compensation keep all clients in sync.
4. **Room Modes:** Configurable per room:
   - `host_only`: Host has exclusive control over playback; guests can suggest tracks.
   - `collaborative`: Any participant can control playback and manage the queue.
5. **Zero AI Slop / S-Grade Human Code Quality:** Idiomatic Go using standard concurrency patterns (`sync.RWMutex`, goroutines, channels, read/write pumps), clean package boundaries, explicit error handling, and zero bloated generated abstractions.
6. **Zero-Dependency Ephemeral Memory Engine:** No external databases required. Rooms and state live safely in memory, reclaiming resources cleanly when sessions end.

---

## 2. System Architecture

The server runs as a standalone Go service organized in a dedicated directory `lastwave-sync/`:

```
lastwave-sync/
├── cmd/
│   └── server/
│       └── main.go              # CLI entry point, env configuration, signal handling
├── internal/
│   ├── protocol/
│   │   ├── messages.go          # WebSocket message types, requests, events & payloads
│   │   └── validation.go        # Display name, track payload, and chat sanitization
│   ├── sync/
│   │   ├── client.go            # WebSocket connection lifecycle, readPump, writePump
│   │   ├── hub.go               # Room registry, code generator, and periodic cleanup
│   │   ├── room.go              # Room state, participant management, host migration
│   │   ├── playback.go          # Playback state, clock synchronization, transit compensation
│   │   └── chat.go              # Chat message creation, rolling history buffer
│   └── server/
│       ├── handler.go           # HTTP endpoints (/health, /stats, /ws upgrade)
│       └── server.go            # HTTP server instance and graceful shutdown
├── go.mod                       # Go module definition
├── README.md                    # Setup, running instructions, and protocol reference
└── internal/sync/sync_test.go   # End-to-end integration and unit tests
```

---

## 3. Wire Protocol & Message Specs

All WebSocket communication occurs over JSON messages with a consistent outer envelope:

```json
{
  "type": "<message_type>",
  "payload": { ... }
}
```

### 3.1 Client -> Server Messages

| Message Type | Payload Fields | Description |
| :--- | :--- | :--- |
| `create_room` | `name` (string), `room_mode` ("host_only" \| "collaborative") | Creates a new room. Sets sender as host. |
| `join_room` | `room_code` (string), `name` (string) | Joins an existing room. Requires display name. |
| `leave_room` | *(empty)* | Gracefully leaves the current room. |
| `send_chat` | `text` (string, 1-500 chars) | Sends a text chat message to all room members. |
| `playback_action` | `action` ("play" \| "pause" \| "seek" \| "change_track" \| "queue_add" \| "queue_remove" \| "queue_clear"), `track` (object), `position_ms` (int64), `client_time_ms` (int64) | Dispatches a playback command. |
| `suggest_track` | `track` (object) | Suggests a track to the host (in `host_only` mode). |
| `approve_suggestion` | `suggestion_id` (string) | Host approves a suggested track (appends/inserts into queue). |
| `reject_suggestion` | `suggestion_id` (string) | Host declines a suggested track. |
| `ping` | `timestamp` (int64) | Latency measurement probe. |

### 3.2 Server -> Client Messages

| Message Type | Payload Fields | Description |
| :--- | :--- | :--- |
| `room_created` | `room_code`, `user_id`, `name`, `is_host`, `room_mode` | Confirmation sent to room creator. |
| `sync_state` | `room_code`, `user_id`, `name`, `is_host`, `room_mode`, `playback`, `participants`, `chat_history` | Sent immediately upon successful room join. |
| `user_joined` | `user_id`, `name`, `is_host`, `total_users` | Broadcast when a new participant enters. |
| `user_left` | `user_id`, `name`, `total_users` | Broadcast when a participant leaves. |
| `chat_message` | `id`, `user_id`, `user_name`, `is_host`, `text`, `timestamp` | Broadcast to all room members. |
| `sync_playback` | `action`, `track`, `position_ms`, `is_playing`, `server_time_ms`, `revision` | Broadcast to synchronize playback across all clients. |
| `host_changed` | `new_host_id`, `new_host_name` | Broadcast when host passes or migrates. |
| `suggestion_received`| `suggestion_id`, `from_user_id`, `from_user_name`, `track` | Sent to host when a guest suggests a track. |
| `pong` | `client_time_ms`, `server_time_ms` | Latency response. |
| `error` | `code`, `message` | Dispatched on any validation failure or unauthorized action. |

---

## 4. Name Validation & Join Flow

1. **Validation Rules:**
   - Display name is trimmed of leading/trailing whitespace.
   - Must be between 1 and 32 characters.
   - Disallows ASCII control characters (`\n`, `\r`, `\t`, etc.).
2. **Failure Handling:**
   - If a client attempts to `join_room` or `create_room` without a valid name:
     - Returns `error` with code `invalid_name` and message `"Display name is required before entering a room (1-32 chars)"`.
     - Connection remains open so the client can retry with a valid name without reconnecting the WebSocket.

---

## 5. Playback Synchronization Logic

### Millisecond Clock Alignment
* When playback is started or paused, the server captures `server_time_ms = time.Now().UnixMilli()`.
* For `play`:
  - `state.IsPlaying = true`
  - `state.PositionMs = position_ms`
  - `state.ServerTimeMs = server_time_ms`
* Clients render current position in real time using:
  ```kotlin
  val currentPos = if (isPlaying) {
      positionMs + (System.currentTimeMillis() - serverTimeMs)
  } else {
      positionMs
  }
  ```
* **Transit Latency Compensation:** The server measures transit delay `server_now - client_time_ms`. If transit was delayed (up to 3 seconds), `position_ms` is compensated to avoid audible jitter across devices.

---

## 6. Concurrency & Safety Model

1. **Hub Safety:** `Hub` guards its room map (`map[string]*Room`) with a `sync.RWMutex`.
2. **Room Safety:** Each `Room` guards its participant map, playback state, queue, and chat ring buffer with an independent `sync.RWMutex`.
3. **Socket Pump Architecture:**
   - Each `Client` runs a dedicated `readPump` goroutine that parses incoming WebSocket frames.
   - A dedicated `writePump` goroutine consumes messages from a buffered channel (`send chan []byte`), ensuring no slow client blocks the room broadcast.
   - Automatic ping/pong keepalives detect dead connections promptly.

---

## 7. Testing & Verification

1. **Unit Tests:**
   - Name validation (valid names, empty names, unicode names, control chars).
   - Playback transit compensation math and position clamping.
   - Chat message formatting, length truncation, and rolling buffer eviction.
2. **Integration Tests:**
   - Creating a room and receiving `room_created`.
   - Joining without name (rejected) vs joining with name (succeeds, receives `sync_state`).
   - Broadcasting chat message to multiple connected clients.
   - Playback sync: Host triggers `play`, all joined clients receive `sync_playback` with synchronized timestamp.
   - Host migration when host disconnects.
