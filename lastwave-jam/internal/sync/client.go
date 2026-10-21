package sync

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Clash-Projects/lastwave-jam/internal/protocol"
	"github.com/gorilla/websocket"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	maxMessageSize = 64 * 1024 // 64 KB
	sendChanSize   = 256
)

var (
	ErrConnectionClosed = errors.New("client connection closed")
)

// Client represents a connected WebSocket user.
type Client struct {
	ID   string
	hub  *Hub
	conn *websocket.Conn

	mu     sync.RWMutex
	name   string
	room   *Room
	closed atomic.Bool
	send   chan []byte
}

// GenerateID returns a cryptographically random hex identifier.
func GenerateID(prefix string) string {
	bytes := make([]byte, 8)
	_, _ = rand.Read(bytes)
	return prefix + hex.EncodeToString(bytes)
}

// NewClient initializes a Client instance for an upgraded WebSocket connection.
func NewClient(conn *websocket.Conn, hub *Hub) *Client {
	return &Client{
		ID:   GenerateID("usr_"),
		hub:  hub,
		conn: conn,
		send: make(chan []byte, sendChanSize),
	}
}

// SetName safely sets the verified display name of the client.
func (c *Client) SetName(name string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.name = name
}

// Name returns the client's current display name.
func (c *Client) Name() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.name
}

// SetRoom sets the room the client is currently participating in.
func (c *Client) SetRoom(r *Room) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.room = r
}

// CurrentRoom returns the room the client is currently in, if any.
func (c *Client) CurrentRoom() *Room {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.room
}

// Send serializes and queues a typed message to the client's write channel.
func (c *Client) Send(msgType string, payload any) error {
	if c.closed.Load() {
		return ErrConnectionClosed
	}

	rawPayload, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	env := protocol.Envelope{
		Type:    msgType,
		Payload: rawPayload,
	}

	msgBytes, err := json.Marshal(env)
	if err != nil {
		return err
	}

	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.closed.Load() {
		return ErrConnectionClosed
	}

	select {
	case c.send <- msgBytes:
		return nil
	default:
		slog.Warn("client send buffer full, dropping message", "user_id", c.ID)
		return errors.New("send buffer full")
	}
}

// SendError delivers an error message payload to the client.
func (c *Client) SendError(code, message string) {
	_ = c.Send(protocol.MsgTypeError, protocol.ErrorPayload{
		Code:    code,
		Message: message,
	})
}

// Close gracefully closes the client connection and drains resources.
func (c *Client) Close() {
	if c.closed.CompareAndSwap(false, true) {
		c.mu.Lock()
		close(c.send)
		c.mu.Unlock()

		if c.conn != nil {
			_ = c.conn.Close()
		}
	}
}

// ReadPump reads incoming JSON messages from the WebSocket connection.
func (c *Client) ReadPump() {
	defer func() {
		if r := c.CurrentRoom(); r != nil {
			r.Leave(c)
		}
		c.Close()
	}()

	c.conn.SetReadLimit(maxMessageSize)
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		_, message, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				slog.Debug("websocket read error", "user_id", c.ID, "err", err)
			}
			break
		}

		var env protocol.Envelope
		if err := json.Unmarshal(message, &env); err != nil {
			c.SendError("invalid_json", "Message must be valid JSON envelope")
			continue
		}

		c.dispatch(env)
	}
}

// WritePump pumps queued messages from the send channel to the WebSocket connection.
func (c *Client) WritePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		if c.conn != nil {
			_ = c.conn.Close()
		}
	}()

	for {
		select {
		case msg, ok := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				// Send channel was closed
				_ = c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			w, err := c.conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			if _, err := w.Write(msg); err != nil {
				return
			}
			if err := w.Close(); err != nil {
				return
			}

		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// dispatch routes incoming envelopes to the hub or room handler.
func (c *Client) dispatch(env protocol.Envelope) {
	switch env.Type {
	case protocol.MsgTypePing:
		var pingTimestamp int64
		_ = json.Unmarshal(env.Payload, &pingTimestamp)
		_ = c.Send(protocol.MsgTypePong, map[string]int64{
			"client_time_ms": pingTimestamp,
			"server_time_ms": time.Now().UnixMilli(),
		})

	case protocol.MsgTypeCreateRoom:
		if c.hub != nil {
			var p protocol.CreateRoomPayload
			if err := json.Unmarshal(env.Payload, &p); err != nil {
				c.SendError("invalid_payload", "Invalid create_room payload")
				return
			}
			_, _ = c.hub.CreateRoom(c, p.Name, p.RoomMode)
		}

	case protocol.MsgTypeJoinRoom:
		if c.hub != nil {
			var p protocol.JoinRoomPayload
			if err := json.Unmarshal(env.Payload, &p); err != nil {
				c.SendError("invalid_payload", "Invalid join_room payload")
				return
			}
			_ = c.hub.JoinRoom(c, p.RoomCode, p.Name)
		}

	case protocol.MsgTypeLeaveRoom:
		if r := c.CurrentRoom(); r != nil {
			r.Leave(c)
		}

	case protocol.MsgTypeSendChat:
		r := c.CurrentRoom()
		if r == nil {
			c.SendError("not_in_room", "You must be in a room to send messages")
			return
		}
		var p protocol.SendChatPayload
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			c.SendError("invalid_payload", "Invalid send_chat payload")
			return
		}
		r.HandleChat(c, p.Text)

	case protocol.MsgTypePlaybackAction:
		r := c.CurrentRoom()
		if r == nil {
			c.SendError("not_in_room", "You must be in a room to control playback")
			return
		}
		var p protocol.PlaybackActionPayload
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			c.SendError("invalid_payload", "Invalid playback_action payload")
			return
		}
		r.HandlePlayback(c, p)

	case protocol.MsgTypeSuggestTrack:
		r := c.CurrentRoom()
		if r == nil {
			c.SendError("not_in_room", "You must be in a room to suggest tracks")
			return
		}
		var p protocol.SuggestTrackPayload
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			c.SendError("invalid_payload", "Invalid suggest_track payload")
			return
		}
		r.HandleSuggestTrack(c, p.Track)

	case protocol.MsgTypeApproveSuggestion:
		r := c.CurrentRoom()
		if r == nil {
			c.SendError("not_in_room", "You must be in a room to approve suggestions")
			return
		}
		var p protocol.SuggestionDecisionPayload
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			c.SendError("invalid_payload", "Invalid approve_suggestion payload")
			return
		}
		r.HandleApproveSuggestion(c, p.SuggestionID)

	case protocol.MsgTypeRejectSuggestion:
		r := c.CurrentRoom()
		if r == nil {
			c.SendError("not_in_room", "You must be in a room to reject suggestions")
			return
		}
		var p protocol.SuggestionDecisionPayload
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			c.SendError("invalid_payload", "Invalid reject_suggestion payload")
			return
		}
		r.HandleRejectSuggestion(c, p.SuggestionID)

	default:
		c.SendError("unknown_message_type", "Unknown message type: "+env.Type)
	}
}
