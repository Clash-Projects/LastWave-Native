package sync

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/Clash-Projects/lastwave-sync/internal/protocol"
)

// mockClient creates a test client with buffered channel.
func mockClient(id, name string) *Client {
	c := &Client{
		ID:   id,
		name: name,
		send: make(chan []byte, 100),
	}
	return c
}

// readLastEnvelope reads the latest envelope sent to a mock client.
func readLastEnvelope(t *testing.T, c *Client) *protocol.Envelope {
	t.Helper()
	select {
	case msg := <-c.send:
		var env protocol.Envelope
		if err := json.Unmarshal(msg, &env); err != nil {
			t.Fatalf("failed to unmarshal sent envelope: %v", err)
		}
		return &env
	case <-time.After(50 * time.Millisecond):
		return nil
	}
}

func TestRoom_JoinRequiresValidName(t *testing.T) {
	host := mockClient("usr_host", "HostUser")
	room := NewRoom("WAVE01", host, protocol.RoomModeHostOnly)

	guest := mockClient("usr_guest", "")

	// Empty name rejected
	err := room.Join(guest, "")
	if err == nil {
		t.Fatal("expected error joining with empty name")
	}

	// Whitespace only rejected
	err = room.Join(guest, "   \n\t  ")
	if err == nil {
		t.Fatal("expected error joining with whitespace-only name")
	}

	// Valid name succeeds
	err = room.Join(guest, "Sarah")
	if err != nil {
		t.Fatalf("unexpected error joining with valid name: %v", err)
	}

	if guest.Name() != "Sarah" {
		t.Errorf("expected guest name 'Sarah', got %q", guest.Name())
	}
	if guest.CurrentRoom() != room {
		t.Error("expected guest room reference to be set")
	}
}

func TestRoom_PlaybackPermissions(t *testing.T) {
	host := mockClient("usr_host", "HostUser")
	room := NewRoom("WAVE02", host, protocol.RoomModeHostOnly)

	guest := mockClient("usr_guest", "")
	_ = room.Join(guest, "GuestUser")

	// Drain initial sync messages
	_ = readLastEnvelope(t, host)
	_ = readLastEnvelope(t, guest)

	// Non-host attempts to play in host_only mode
	action := protocol.PlaybackActionPayload{
		Action:       protocol.ActionPlay,
		PositionMs:   1000,
		ClientTimeMs: time.Now().UnixMilli(),
	}
	room.HandlePlayback(guest, action)

	env := readLastEnvelope(t, guest)
	if env == nil || env.Type != protocol.MsgTypeError {
		t.Fatalf("expected error envelope for guest in host_only mode, got %v", env)
	}

	// Host plays in host_only mode -> succeeds
	room.HandlePlayback(host, action)
	hostEnv := readLastEnvelope(t, host)
	if hostEnv == nil || hostEnv.Type != protocol.MsgTypeSyncPlayback {
		t.Fatalf("expected sync_playback for host, got %v", hostEnv)
	}

	guestEnv := readLastEnvelope(t, guest)
	if guestEnv == nil || guestEnv.Type != protocol.MsgTypeSyncPlayback {
		t.Fatalf("expected sync_playback broadcast to guest, got %v", guestEnv)
	}
}

func TestRoom_CollaborativeModeAllowsGuestPlayback(t *testing.T) {
	host := mockClient("usr_host", "HostUser")
	room := NewRoom("WAVE03", host, protocol.RoomModeCollaborative)

	guest := mockClient("usr_guest", "")
	_ = room.Join(guest, "GuestUser")

	// Drain join messages
	_ = readLastEnvelope(t, host)
	_ = readLastEnvelope(t, guest)

	// Guest plays in collaborative mode -> succeeds
	action := protocol.PlaybackActionPayload{
		Action:       protocol.ActionPlay,
		PositionMs:   2500,
		ClientTimeMs: time.Now().UnixMilli(),
	}
	room.HandlePlayback(guest, action)

	guestEnv := readLastEnvelope(t, guest)
	if guestEnv == nil || guestEnv.Type != protocol.MsgTypeSyncPlayback {
		t.Fatalf("expected sync_playback for guest in collaborative mode, got %v", guestEnv)
	}
}

func TestRoom_ChatBroadcast(t *testing.T) {
	host := mockClient("usr_host", "HostUser")
	room := NewRoom("WAVE04", host, protocol.RoomModeHostOnly)

	guest := mockClient("usr_guest", "")
	_ = room.Join(guest, "Sarah")

	_ = readLastEnvelope(t, host)
	_ = readLastEnvelope(t, guest)

	// Sarah sends a chat message
	room.HandleChat(guest, "Hello everyone!")

	hostEnv := readLastEnvelope(t, host)
	if hostEnv == nil || hostEnv.Type != protocol.MsgTypeChatMessage {
		t.Fatalf("expected chat_message to host, got %v", hostEnv)
	}

	guestEnv := readLastEnvelope(t, guest)
	if guestEnv == nil || guestEnv.Type != protocol.MsgTypeChatMessage {
		t.Fatalf("expected chat_message to guest, got %v", guestEnv)
	}

	// Verify message in chat history buffer
	history := room.ChatHistory()
	if len(history) != 1 || history[0].Text != "Hello everyone!" {
		t.Fatalf("expected 1 message in history, got %v", history)
	}
}

func TestRoom_HostMigrationOnLeave(t *testing.T) {
	host := mockClient("usr_host", "OriginalHost")
	room := NewRoom("WAVE05", host, protocol.RoomModeHostOnly)

	guest := mockClient("usr_guest", "")
	_ = room.Join(guest, "NewHostCandidate")

	_ = readLastEnvelope(t, host)
	_ = readLastEnvelope(t, guest)

	// Host leaves
	room.Leave(host)

	// Guest should receive user_left notification first
	envLeft := readLastEnvelope(t, guest)
	if envLeft == nil || envLeft.Type != protocol.MsgTypeUserLeft {
		t.Fatalf("expected user_left notification, got %v", envLeft)
	}

	// Guest should then receive host_changed notification
	envHost := readLastEnvelope(t, guest)
	if envHost == nil || envHost.Type != protocol.MsgTypeHostChanged {
		t.Fatalf("expected host_changed notification to remaining user, got %v", envHost)
	}

	if room.HostID() != guest.ID {
		t.Errorf("expected host ID %q, got %q", guest.ID, room.HostID())
	}
}
