package sync

import (
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/Clash-Projects/lastwave-sync/internal/protocol"
)

func TestChatBuffer_Empty(t *testing.T) {
	buf := NewChatBuffer(10)
	history := buf.History()
	if history == nil {
		t.Fatal("expected non-nil empty history slice")
	}
	if len(history) != 0 {
		t.Fatalf("expected length 0, got %d", len(history))
	}
}

func TestChatBuffer_AddAndHistory(t *testing.T) {
	buf := NewChatBuffer(3)

	for i := 1; i <= 5; i++ {
		buf.Add(protocol.ChatMessage{
			ID:        fmt.Sprintf("msg_%d", i),
			UserID:    "usr_1",
			UserName:  "Alice",
			Text:      fmt.Sprintf("Message %d", i),
			Timestamp: time.Now().UnixMilli(),
		})
	}

	history := buf.History()
	if len(history) != 3 {
		t.Fatalf("expected 3 messages, got %d", len(history))
	}

	// Should contain msg_3, msg_4, msg_5 in that order
	expectedIDs := []string{"msg_3", "msg_4", "msg_5"}
	for i, wantID := range expectedIDs {
		if history[i].ID != wantID {
			t.Errorf("history[%d].ID = %q, want %q", i, history[i].ID, wantID)
		}
	}
}

func TestChatBuffer_Concurrency(t *testing.T) {
	buf := NewChatBuffer(50)
	var wg sync.WaitGroup

	// 10 concurrent writers
	for w := 0; w < 10; w++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			for i := 0; i < 20; i++ {
				buf.Add(protocol.ChatMessage{
					ID:        fmt.Sprintf("worker_%d_msg_%d", workerID, i),
					UserID:    fmt.Sprintf("usr_%d", workerID),
					UserName:  "Worker",
					Text:      "test",
					Timestamp: time.Now().UnixMilli(),
				})
			}
		}(w)
	}

	// 5 concurrent readers
	for r := 0; r < 5; r++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 20; i++ {
				_ = buf.History()
			}
		}()
	}

	wg.Wait()

	history := buf.History()
	if len(history) != 50 {
		t.Fatalf("expected buffer to be full at 50 messages, got %d", len(history))
	}
}
