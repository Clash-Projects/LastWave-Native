# LastWave Sync Server

High-performance, low-latency "Listen Together" and in-room chat server for [LastWave Android](https://github.com/Clash-Projects/LastWave-native) and [LastWave Desktop](https://github.com/Clash-Projects/LastWave-Desktop).

---

## Features

- **Frictionless Name-Gated Entry:** Users join listening rooms using a 6-character room code and their display name. No signup or account registration required.
- **In-Room Live Text Chat:** Participants can chat in real time while listening together. Late joiners receive the recent chat history (last 50 messages) automatically upon room entry.
- **Sub-Millisecond Playback Synchronization:** Host-synchronized playback (`play`, `pause`, `seek`, `change_track`, `queue_add`, `skip_next`) with server transit compensation to ensure zero drift across devices.
- **Configurable Room Modes:**
  - `host_only`: The host has master control over playback. Guests can suggest tracks (`suggest_track`) which the host can accept or decline.
  - `collaborative`: Open DJ mode where all participants can play, pause, seek, and modify the queue.
- **Host Migration:** If the host leaves or disconnects, host status automatically migrates to the next longest-tenured member in the room.
- **Zero-Dependency Ephemeral Memory:** In-memory state engine designed for fast ephemeral sessions without database lock contention.
- **Clean Idiomatic Go:** Pure Go concurrency with standard library patterns (`sync.RWMutex`, goroutines, channels, read/write pumps) and zero external runtime dependencies.

---

## Quickstart

### 1. Run with Go

```bash
cd lastwave-sync

# Run on default port (8080)
go run ./cmd/server

# Run on custom port
PORT=9000 go run ./cmd/server
```

### 2. Build Standalone Binary

```bash
cd lastwave-sync
go build -o bin/server ./cmd/server
./bin/server
```

### 3. Run Test Suite

```bash
cd lastwave-sync
go test -race -v ./...
```

---

## HTTP Endpoints

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/health` | `GET` | Service status, uptime, and version |
| `/stats` | `GET` | Active room count and total connected participants |
| `/ws` | `GET` | WebSocket endpoint for real-time synchronization & chat |

---

## WebSocket Protocol Specification

All frames use JSON envelopes:

```json
{
  "type": "<message_type>",
  "payload": { ... }
}
```

### 1. Creating a Room

**Client -> Server:**
```json
{
  "type": "create_room",
  "payload": {
    "name": "Ajisth",
    "room_mode": "host_only"
  }
}
```

**Server -> Client (`room_created`):**
```json
{
  "type": "room_created",
  "payload": {
    "room_code": "WAVE88",
    "user_id": "usr_cbb8d2681636b8db",
    "name": "Ajisth",
    "is_host": true,
    "room_mode": "host_only"
  }
}
```

---

### 2. Joining a Room (Name Required)

**Client -> Server:**
```json
{
  "type": "join_room",
  "payload": {
    "room_code": "WAVE88",
    "name": "Sarah"
  }
}
```

*Note: If `name` is blank or invalid, the server immediately rejects the request with code `invalid_name`:*
```json
{
  "type": "error",
  "payload": {
    "code": "invalid_name",
    "message": "Display name is required before entering a room (1-32 chars)"
  }
}
```

**Server -> Client (`sync_state`):**
```json
{
  "type": "sync_state",
  "payload": {
    "room_code": "WAVE88",
    "user_id": "usr_adca923c32d1c656",
    "name": "Sarah",
    "is_host": false,
    "room_mode": "host_only",
    "playback": {
      "current_track": {
        "id": "yt_dQw4w9WgXcQ",
        "title": "Starboy",
        "artist": "The Weeknd",
        "duration_ms": 230000
      },
      "position_ms": 14200,
      "server_time_ms": 1729500000000,
      "is_playing": true,
      "queue": [],
      "revision": 3
    },
    "participants": [
      { "user_id": "usr_cbb8d2681636b8db", "name": "Ajisth", "is_host": true },
      { "user_id": "usr_adca923c32d1c656", "name": "Sarah", "is_host": false }
    ],
    "chat_history": [
      {
        "id": "msg_01JAX7K8M9",
        "user_id": "usr_cbb8d2681636b8db",
        "user_name": "Ajisth",
        "is_host": true,
        "text": "Welcome to the session!",
        "timestamp": 1729499990000
      }
    ]
  }
}
```

---

### 3. In-Room Live Chat

**Send Chat (Client -> Server):**
```json
{
  "type": "send_chat",
  "payload": {
    "text": "This track is unbelievable!"
  }
}
```

**Chat Message Broadcast (Server -> All Room Members):**
```json
{
  "type": "chat_message",
  "payload": {
    "id": "msg_01JAX7K8M9",
    "user_id": "usr_adca923c32d1c656",
    "user_name": "Sarah",
    "is_host": false,
    "text": "This track is unbelievable!",
    "timestamp": 1729500005000
  }
}
```

---

### 4. Playback Synchronization

**Dispatch Action (Client -> Server):**
```json
{
  "type": "playback_action",
  "payload": {
    "action": "play",
    "position_ms": 15000,
    "client_time_ms": 1729500004950
  }
}
```

**Playback Broadcast (Server -> All Room Members):**
```json
{
  "type": "sync_playback",
  "payload": {
    "action": "play",
    "track": {
      "id": "yt_dQw4w9WgXcQ",
      "title": "Starboy",
      "artist": "The Weeknd",
      "duration_ms": 230000
    },
    "position_ms": 15050,
    "server_time_ms": 1729500005000,
    "is_playing": true,
    "queue": [],
    "revision": 4
  }
}
```

**Client Clock Formula (Android / Desktop):**
```kotlin
val currentPos = if (isPlaying) {
    positionMs + (System.currentTimeMillis() - serverTimeMs)
} else {
    positionMs
}
```

---

### 5. Latency Ping / Pong

**Client -> Server:**
```json
{
  "type": "ping",
  "payload": 1729500000000
}
```

**Server -> Client:**
```json
{
  "type": "pong",
  "payload": {
    "client_time_ms": 1729500000000,
    "server_time_ms": 1729500000025
  }
}
```

---

## License

Part of the open-source LastWave ecosystem. Distributed under the MIT License.
