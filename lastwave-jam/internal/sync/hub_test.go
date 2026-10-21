package sync

import (
	"context"
	"testing"
	"time"

	"github.com/Clash-Projects/lastwave-jam/internal/protocol"
)

func TestHub_CreateRoomAndJoin(t *testing.T) {
	hub := NewHub()
	host := mockClient("usr_host", "")

	// Creating room without name fails
	_, err := hub.CreateRoom(host, "", protocol.RoomModeHostOnly)
	if err == nil {
		t.Fatal("expected error creating room without name")
	}

	// Creating room with valid name succeeds
	room, err := hub.CreateRoom(host, "Alice", protocol.RoomModeHostOnly)
	if err != nil {
		t.Fatalf("unexpected error creating room: %v", err)
	}

	if len(room.Code) != 6 {
		t.Fatalf("expected 6-char room code, got %q", room.Code)
	}

	if hub.RoomCount() != 1 {
		t.Fatalf("expected 1 room, got %d", hub.RoomCount())
	}

	// Joining nonexistent room fails
	guest := mockClient("usr_guest", "")
	err = hub.JoinRoom(guest, "NONEX1", "Bob")
	if err != ErrRoomNotFound {
		t.Fatalf("expected ErrRoomNotFound, got %v", err)
	}

	// Joining existing room without name fails
	err = hub.JoinRoom(guest, room.Code, "")
	if err == nil {
		t.Fatal("expected error joining without name")
	}

	// Joining existing room with valid name succeeds
	err = hub.JoinRoom(guest, room.Code, "Bob")
	if err != nil {
		t.Fatalf("unexpected error joining: %v", err)
	}

	if hub.TotalParticipants() != 2 {
		t.Fatalf("expected 2 total participants, got %d", hub.TotalParticipants())
	}
}

func TestHub_CleanupLoop(t *testing.T) {
	hub := NewHub()
	host := mockClient("usr_host", "")
	room, _ := hub.CreateRoom(host, "Alice", protocol.RoomModeHostOnly)

	// Host leaves -> room is empty
	room.Leave(host)
	if !room.IsEmpty() {
		t.Fatal("expected room to be empty")
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Run cleanup with fast tick
	go hub.RunCleanupLoop(ctx, 10*time.Millisecond)

	time.Sleep(50 * time.Millisecond)

	if hub.RoomCount() != 0 {
		t.Fatalf("expected empty room to be cleaned up, got %d rooms", hub.RoomCount())
	}
}
