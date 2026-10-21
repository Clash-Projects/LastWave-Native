package sync

import (
	"sync"

	"github.com/Clash-Projects/lastwave-jam/internal/protocol"
)

// DefaultChatHistoryLimit is the maximum number of recent messages retained in RAM.
const DefaultChatHistoryLimit = 50

// ChatBuffer stores a bounded, thread-safe history of recent chat messages.
type ChatBuffer struct {
	mu       sync.RWMutex
	capacity int
	messages []protocol.ChatMessage
}

// NewChatBuffer creates a new rolling ring buffer with the specified capacity.
func NewChatBuffer(capacity int) *ChatBuffer {
	if capacity <= 0 {
		capacity = DefaultChatHistoryLimit
	}
	return &ChatBuffer{
		capacity: capacity,
		messages: make([]protocol.ChatMessage, 0, capacity),
	}
}

// Add appends a message to the buffer, evicting the oldest message if at capacity.
func (b *ChatBuffer) Add(msg protocol.ChatMessage) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if len(b.messages) >= b.capacity {
		// Evict the oldest message (FIFO)
		b.messages = append(b.messages[1:], msg)
	} else {
		b.messages = append(b.messages, msg)
	}
}

// History returns a snapshot of stored messages in chronological order.
func (b *ChatBuffer) History() []protocol.ChatMessage {
	b.mu.RLock()
	defer b.mu.RUnlock()

	result := make([]protocol.ChatMessage, len(b.messages))
	copy(result, b.messages)
	return result
}
