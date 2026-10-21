# LastWave Sync Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a high-performance Go "Listen Together" backend with name-gated room entry, in-room real-time text chat, and zero-drift playback synchronization.

**Architecture:** Modular in-memory hub and room state machine using WebSockets with JSON framing, `sync.RWMutex`, and per-client `readPump`/`writePump` goroutines.

**Tech Stack:** Go (1.22+), `github.com/gorilla/websocket`, `net/http`, `log/slog`.

**Spec:** `docs/superpowers/specs/2026-10-21-lastwave-sync-server-design.md`

## Global Constraints

- Display name is mandatory before room entry (1-32 characters, whitespace trimmed, no control characters).
- In-room chat supported for all participants with a rolling 50-message buffer provided upon entry.
- Zero external DB dependency (ephemeral in-memory state engine).
- Zero mention or trace of `metrolist` anywhere. Pure original LastWave branding.
- S-grade, truly human-written Go code: clean packages, clear error handling, idiomatic concurrency, no AI slop or generated boilerplate.

## Review Focus

1. Blank, whitespace-only, or control-character display name -> rejected immediately with `invalid_name`.
2. Action dispatched while client is not in a room -> rejected with `not_in_room`.
3. Guest attempting direct playback action in `host_only` room mode -> rejected with `unauthorized`.
4. Host disconnects or leaves room -> automatic promotion of oldest active member to host with `host_changed`.
5. Oversized chat message (>500 chars) -> truncated or rejected with `message_too_long`.

---

### Task 1: Project Scaffolding & Protocol Definitions

**Files:**
- Create: `lastwave-sync/go.mod`
- Create: `lastwave-sync/internal/protocol/messages.go`
- Create: `lastwave-sync/internal/protocol/validation.go`
- Test: `lastwave-sync/internal/protocol/validation_test.go`

**Interfaces:**
- Produces: `protocol.ValidateName(name string) (string, error)`
- Produces: `protocol.SanitizeChatText(text string) (string, error)`
- Produces: `protocol.Envelope`, `protocol.CreateRoomPayload`, `protocol.JoinRoomPayload`, `protocol.PlaybackActionPayload`, `protocol.ChatPayload`

- [ ] **Step 1: Initialize go.mod and install dependencies**

Run: `go mod init github.com/Clash-Projects/lastwave-sync` and install `github.com/gorilla/websocket`.

- [ ] **Step 2: Write failing tests for validation logic in `internal/protocol/validation_test.go`**

Test cases:
- Empty name returns error.
- Whitespace-only name returns error.
- Name > 32 characters returns error.
- Name with control chars (`\n`, `\t`) is rejected or stripped.
- Valid name `"Ajisth"` passes cleanly.
- Chat message > 500 chars returns error.
- Valid chat text passes cleanly.

- [ ] **Step 3: Run validation tests to confirm failure**

Run: `go test -v ./internal/protocol/...`

- [ ] **Step 4: Implement message structs & validation in `internal/protocol`**

Define typed request/response structs, error codes, and validation functions in `messages.go` and `validation.go`.

- [ ] **Step 5: Run validation tests to confirm pass**

Run: `go test -v ./internal/protocol/...`

- [ ] **Step 6: Commit**

```bash
git add lastwave-sync/
git commit -m "feat(sync): scaffold project and protocol validation"
```

---

### Task 2: In-Room Live Chat & History Ring Buffer

**Files:**
- Create: `lastwave-sync/internal/sync/chat.go`
- Test: `lastwave-sync/internal/sync/chat_test.go`

**Interfaces:**
- Consumes: `protocol.ChatMessage` from Task 1
- Produces: `NewChatBuffer(capacity int) *ChatBuffer`
- Produces: `(b *ChatBuffer) Add(msg protocol.ChatMessage)`
- Produces: `(b *ChatBuffer) History() []protocol.ChatMessage`

- [ ] **Step 1: Write failing tests for chat buffer in `chat_test.go`**

Test cases:
- Adding messages respects ring buffer FIFO eviction when exceeding capacity (e.g., 50).
- `History()` returns messages in correct chronological order.
- Thread-safety under concurrent `Add` and `History` calls.

- [ ] **Step 2: Run test to verify failure**

Run: `go test -v ./internal/sync/ -run TestChat`

- [ ] **Step 3: Implement `ChatBuffer` in `internal/sync/chat.go`**

Thread-safe circular ring buffer with mutex protection and capacity cap.

- [ ] **Step 4: Run test to verify pass**

Run: `go test -v ./internal/sync/ -run TestChat`

- [ ] **Step 5: Commit**

```bash
git add lastwave-sync/internal/sync/chat*
git commit -m "feat(sync): implement thread-safe chat history buffer"
```

---

### Task 3: Playback Clock & Latency Transit Compensation

**Files:**
- Create: `lastwave-sync/internal/sync/playback.go`
- Test: `lastwave-sync/internal/sync/playback_test.go`

**Interfaces:**
- Consumes: `protocol.TrackInfo`, `protocol.PlaybackState` from Task 1
- Produces: `CompensateTransit(posMs int64, clientTimeMs int64, serverTimeMs int64) int64`
- Produces: `CalculateCurrentPosition(state *protocol.PlaybackState, nowMs int64) int64`
- Produces: `SanitizeTrack(track *protocol.TrackInfo) bool`

- [ ] **Step 1: Write failing tests for playback transit compensation & calculations**

Test cases:
- `CompensateTransit` adds delay when `clientTimeMs < serverTimeMs` (capped at 3s).
- `CalculateCurrentPosition` returns constant position when paused.
- `CalculateCurrentPosition` projects position based on elapsed time when playing.
- `SanitizeTrack` validates track `ID` and `Title`.

- [ ] **Step 2: Run test to verify failure**

Run: `go test -v ./internal/sync/ -run TestPlayback`

- [ ] **Step 3: Implement playback timeline math in `internal/sync/playback.go`**

Implement time calculation helpers and track sanitization.

- [ ] **Step 4: Run test to verify pass**

Run: `go test -v ./internal/sync/ -run TestPlayback`

- [ ] **Step 5: Commit**

```bash
git add lastwave-sync/internal/sync/playback*
git commit -m "feat(sync): implement playback timeline and clock math"
```

---

### Task 4: WebSocket Client Connection & Pumps

**Files:**
- Create: `lastwave-sync/internal/sync/client.go`

**Interfaces:**
- Consumes: `gorilla/websocket`
- Produces: `NewClient(hub *Hub, conn *websocket.Conn) *Client`
- Produces: `(c *Client) ReadPump()`
- Produces: `(c *Client) WritePump()`
- Produces: `(c *Client) Send(msgType string, payload any)`
- Produces: `(c *Client) Close()`

- [ ] **Step 1: Implement `Client` struct, channel buffering, and pump loops**

Include ping/pong keepalive tickers, write deadline configuration, and safe closure of buffered channels.

- [ ] **Step 2: Commit**

```bash
git add lastwave-sync/internal/sync/client.go
git commit -m "feat(sync): implement client websocket read and write pumps"
```

---

### Task 5: Room State Machine & Permissions

**Files:**
- Create: `lastwave-sync/internal/sync/room.go`
- Test: `lastwave-sync/internal/sync/room_test.go`

**Interfaces:**
- Consumes: `Client`, `ChatBuffer`, `playback` helpers
- Produces: `NewRoom(code string, host *Client, mode string) *Room`
- Produces: `(r *Room) Join(c *Client, name string) error`
- Produces: `(r *Room) Leave(c *Client)`
- Produces: `(r *Room) Broadcast(msgType string, payload any)`
- Produces: `(r *Room) HandleChat(c *Client, text string)`
- Produces: `(r *Room) HandlePlayback(c *Client, action protocol.PlaybackActionPayload)`

- [ ] **Step 1: Write failing tests for room operations in `room_test.go`**

Test cases:
- Joining with blank name returns error.
- Joining with valid name adds client to room and dispatches state.
- Chat message broadcasts to all connected room members.
- Host-only mode rejects playback actions from non-host.
- Collaborative mode allows playback actions from non-host.
- Host leaving triggers automatic promotion of the next member.

- [ ] **Step 2: Run test to verify failure**

Run: `go test -v ./internal/sync/ -run TestRoom`

- [ ] **Step 3: Implement `Room` state machine in `internal/sync/room.go`**

Implement room locking, participant tracking, host election, chat handling, and playback action dispatch.

- [ ] **Step 4: Run test to verify pass**

Run: `go test -v ./internal/sync/ -run TestRoom`

- [ ] **Step 5: Commit**

```bash
git add lastwave-sync/internal/sync/room*
git commit -m "feat(sync): implement room state machine and permission logic"
```

---

### Task 6: Global Hub & Room Lifecycle

**Files:**
- Create: `lastwave-sync/internal/sync/hub.go`
- Test: `lastwave-sync/internal/sync/hub_test.go`

**Interfaces:**
- Produces: `NewHub() *Hub`
- Produces: `(h *Hub) CreateRoom(c *Client, name string, mode string) (*Room, error)`
- Produces: `(h *Hub) GetRoom(code string) (*Room, bool)`
- Produces: `(h *Hub) RemoveRoom(code string)`
- Produces: `(h *Hub) RunCleanupLoop(ctx context.Context, interval time.Duration)`

- [ ] **Step 1: Write failing tests for Hub room management in `hub_test.go`**

Test cases:
- `CreateRoom` generates clean 6-character room codes without collision.
- `GetRoom` retrieves existing room.
- Empty rooms are cleaned up after grace period.

- [ ] **Step 2: Run test to verify failure**

Run: `go test -v ./internal/sync/ -run TestHub`

- [ ] **Step 3: Implement `Hub` in `internal/sync/hub.go`**

Implement room registry, random code generator with alphanumeric charset, and cleanup loop.

- [ ] **Step 4: Run test to verify pass**

Run: `go test -v ./internal/sync/ -run TestHub`

- [ ] **Step 5: Commit**

```bash
git add lastwave-sync/internal/sync/hub*
git commit -m "feat(sync): implement central room hub and lifecycle manager"
```

---

### Task 7: HTTP Handlers, WebSocket Upgrade & Main Server Entry

**Files:**
- Create: `lastwave-sync/internal/server/handler.go`
- Create: `lastwave-sync/internal/server/server.go`
- Create: `lastwave-sync/cmd/server/main.go`

**Interfaces:**
- Produces: `server.New(cfg Config, hub *sync.Hub) *Server`
- Produces: `GET /health` -> 200 OK
- Produces: `GET /stats` -> Active rooms & participant count
- Produces: `GET /ws` -> WebSocket upgrade & client registration

- [ ] **Step 1: Implement HTTP routes and WebSocket upgrader in `handler.go`**
- [ ] **Step 2: Implement server lifecycle and graceful shutdown in `server.go`**
- [ ] **Step 3: Implement CLI entry point with environment variable configuration in `cmd/server/main.go`**
- [ ] **Step 4: Verify server builds cleanly**

Run: `go build -o /dev/null ./cmd/server` (or `go build -o ./bin/server.exe ./cmd/server`)

- [ ] **Step 5: Commit**

```bash
git add lastwave-sync/internal/server/ lastwave-sync/cmd/
git commit -m "feat(sync): implement http server, websocket upgrader and cli entry"
```

---

### Task 8: End-to-End WebSocket Integration Tests & Documentation

**Files:**
- Create: `lastwave-sync/internal/sync/sync_test.go`
- Create: `lastwave-sync/README.md`

- [ ] **Step 1: Write comprehensive end-to-end WebSocket test suite**

Cover full flow over real HTTP WebSocket server:
1. Client A connects and creates room `"WAVE01"` with name `"Alice"`.
2. Client B connects and attempts to join `"WAVE01"` without name -> receives `invalid_name` error.
3. Client B joins `"WAVE01"` with name `"Bob"` -> receives `sync_state` containing Alice as host.
4. Alice sends chat `"Hey Bob!"` -> Bob receives `chat_message` with Alice's name and timestamp.
5. Alice triggers `play` -> Bob receives `sync_playback` with synchronized position and server time.
6. Alice disconnects -> Bob receives `host_changed` promoting Bob to host.

- [ ] **Step 2: Run all tests in repository**

Run: `go test -v -race ./...`

- [ ] **Step 3: Create complete `README.md`**

Include quickstart guide, environment variables, full WebSocket JSON protocol examples, and architecture overview.

- [ ] **Step 4: Commit**

```bash
git add lastwave-sync/
git commit -m "test(sync): add end-to-end integration tests and documentation"
```
