package sync

import (
	"sync"
	"time"

	"github.com/Clash-Projects/lastwave-sync/internal/protocol"
)

// Room manages the concurrent state of a live listening session.
type Room struct {
	Code      string
	Mode      string
	CreatedAt time.Time

	mu                  sync.RWMutex
	host                *Client
	participants        map[string]*Client
	joinedOrder         []string
	state               protocol.PlaybackState
	chat                *ChatBuffer
	pendingSuggestions  map[string]*protocol.SuggestionReceivedPayload
}

// NewRoom creates a new listening room with the specified host and mode.
func NewRoom(code string, host *Client, mode string) *Room {
	if mode != protocol.RoomModeCollaborative {
		mode = protocol.RoomModeHostOnly
	}

	r := &Room{
		Code:               code,
		Mode:               mode,
		CreatedAt:          time.Now(),
		host:               host,
		participants:       make(map[string]*Client),
		joinedOrder:        make([]string, 0),
		chat:               NewChatBuffer(DefaultChatHistoryLimit),
		pendingSuggestions: make(map[string]*protocol.SuggestionReceivedPayload),
		state: protocol.PlaybackState{
			Queue:        make([]protocol.TrackInfo, 0),
			ServerTimeMs: time.Now().UnixMilli(),
		},
	}

	if host != nil {
		r.participants[host.ID] = host
		r.joinedOrder = append(r.joinedOrder, host.ID)
		host.SetRoom(r)
	}

	return r
}

// HostID returns the user ID of the current room host.
func (r *Room) HostID() string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if r.host != nil {
		return r.host.ID
	}
	return ""
}

// IsEmpty returns true if there are no participants remaining.
func (r *Room) IsEmpty() bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.participants) == 0
}

// ParticipantCount returns the number of currently connected users.
func (r *Room) ParticipantCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.participants)
}

// ChatHistory returns recent messages stored in the room's chat buffer.
func (r *Room) ChatHistory() []protocol.ChatMessage {
	return r.chat.History()
}

// Join adds a client to the room after validating their display name.
func (r *Room) Join(c *Client, rawName string) error {
	name, err := protocol.ValidateName(rawName)
	if err != nil {
		c.SendError("invalid_name", "Display name is required before entering a room (1-32 chars)")
		return err
	}

	c.SetName(name)
	c.SetRoom(r)

	r.mu.Lock()
	r.participants[c.ID] = c
	r.joinedOrder = append(r.joinedOrder, c.ID)

	// Promote as host if room currently has no host
	if r.host == nil {
		r.host = c
	}

	isHost := (r.host != nil && r.host.ID == c.ID)

	// Prepare participants snapshot
	participants := make([]protocol.Participant, 0, len(r.participants))
	for _, p := range r.participants {
		participants = append(participants, protocol.Participant{
			UserID:   p.ID,
			Name:     p.Name(),
			IsHost:   (r.host != nil && r.host.ID == p.ID),
			JoinedAt: time.Now().UnixMilli(),
		})
	}

	// Capture playback state snapshot
	playbackSnapshot := r.state
	playbackSnapshot.PositionMs = CalculateCurrentPosition(&r.state, time.Now().UnixMilli())
	playbackSnapshot.ServerTimeMs = time.Now().UnixMilli()
	chatHistory := r.chat.History()
	totalUsers := len(r.participants)
	r.mu.Unlock()

	// 1. Deliver complete sync_state to the newly joined client
	_ = c.Send(protocol.MsgTypeSyncState, protocol.SyncStatePayload{
		RoomCode:     r.Code,
		UserID:       c.ID,
		Name:         name,
		IsHost:       isHost,
		RoomMode:     r.Mode,
		Playback:     playbackSnapshot,
		Participants: participants,
		ChatHistory:  chatHistory,
	})

	// 2. Broadcast user_joined to all other participants
	r.Broadcast(protocol.MsgTypeUserJoined, map[string]any{
		"user_id":     c.ID,
		"name":        name,
		"is_host":     isHost,
		"total_users": totalUsers,
	}, c.ID)

	return nil
}

// Leave handles graceful departure or disconnect of a participant.
func (r *Room) Leave(c *Client) {
	r.mu.Lock()
	if _, ok := r.participants[c.ID]; !ok {
		r.mu.Unlock()
		return
	}

	delete(r.participants, c.ID)
	c.SetRoom(nil)

	// Remove from joinedOrder
	for i, id := range r.joinedOrder {
		if id == c.ID {
			r.joinedOrder = append(r.joinedOrder[:i], r.joinedOrder[i+1:]...)
			break
		}
	}

	var newHost *Client
	if r.host != nil && r.host.ID == c.ID {
		// Promote next oldest participant
		if len(r.joinedOrder) > 0 {
			newHostID := r.joinedOrder[0]
			newHost = r.participants[newHostID]
			r.host = newHost
		} else {
			r.host = nil
		}
	}

	totalUsers := len(r.participants)
	userName := c.Name()
	r.mu.Unlock()

	// Notify room that user left
	r.Broadcast(protocol.MsgTypeUserLeft, map[string]any{
		"user_id":     c.ID,
		"name":        userName,
		"total_users": totalUsers,
	})

	// Notify room of host change if promoted
	if newHost != nil {
		r.Broadcast(protocol.MsgTypeHostChanged, map[string]any{
			"new_host_id":   newHost.ID,
			"new_host_name": newHost.Name(),
		})
	}
}

// Broadcast sends a message to all connected participants in the room,
// optionally excluding specific client IDs.
func (r *Room) Broadcast(msgType string, payload any, excludeIDs ...string) {
	r.mu.RLock()
	clients := make([]*Client, 0, len(r.participants))
	for id, c := range r.participants {
		skip := false
		for _, ex := range excludeIDs {
			if id == ex {
				skip = true
				break
			}
		}
		if !skip && c != nil {
			clients = append(clients, c)
		}
	}
	r.mu.RUnlock()

	for _, c := range clients {
		_ = c.Send(msgType, payload)
	}
}

// HandleChat processes and broadcasts an incoming in-room chat message.
func (r *Room) HandleChat(c *Client, rawText string) {
	text, err := protocol.SanitizeChatText(rawText)
	if err != nil {
		c.SendError("invalid_chat", err.Error())
		return
	}

	r.mu.RLock()
	isHost := (r.host != nil && r.host.ID == c.ID)
	r.mu.RUnlock()

	msg := protocol.ChatMessage{
		ID:        GenerateID("msg_"),
		UserID:    c.ID,
		UserName:  c.Name(),
		IsHost:    isHost,
		Text:      text,
		Timestamp: time.Now().UnixMilli(),
	}

	r.chat.Add(msg)
	r.Broadcast(protocol.MsgTypeChatMessage, msg)
}

// HandlePlayback handles playback actions (play, pause, seek, queue, etc.).
func (r *Room) HandlePlayback(c *Client, p protocol.PlaybackActionPayload) {
	r.mu.Lock()
	defer r.mu.Unlock()

	isHost := (r.host != nil && r.host.ID == c.ID)
	if r.Mode == protocol.RoomModeHostOnly && !isHost {
		c.SendError("unauthorized", "Only the host can control playback in host-only mode")
		return
	}

	nowMs := time.Now().UnixMilli()

	switch p.Action {
	case protocol.ActionPlay:
		r.state.IsPlaying = true
		r.state.PositionMs = CompensateTransit(p.PositionMs, p.ClientTimeMs, nowMs)
		r.state.ServerTimeMs = nowMs

	case protocol.ActionPause:
		r.state.IsPlaying = false
		r.state.PositionMs = p.PositionMs
		r.state.ServerTimeMs = nowMs

	case protocol.ActionSeek:
		r.state.PositionMs = p.PositionMs
		r.state.ServerTimeMs = nowMs

	case protocol.ActionChangeTrack:
		if p.Track != nil && protocol.SanitizeTrack(p.Track) {
			r.state.CurrentTrack = p.Track
			r.state.PositionMs = 0
			r.state.ServerTimeMs = nowMs
			r.state.IsPlaying = true
		}

	case protocol.ActionQueueAdd:
		if p.Track != nil && protocol.SanitizeTrack(p.Track) {
			r.state.Queue = append(r.state.Queue, *p.Track)
		}

	case protocol.ActionQueueRemove:
		if p.Track != nil && p.Track.ID != "" {
			newQueue := make([]protocol.TrackInfo, 0, len(r.state.Queue))
			for _, t := range r.state.Queue {
				if t.ID != p.Track.ID {
					newQueue = append(newQueue, t)
				}
			}
			r.state.Queue = newQueue
		}

	case protocol.ActionQueueClear:
		r.state.Queue = make([]protocol.TrackInfo, 0)

	case protocol.ActionSkipNext:
		if len(r.state.Queue) > 0 {
			nextTrack := r.state.Queue[0]
			r.state.CurrentTrack = &nextTrack
			r.state.Queue = r.state.Queue[1:]
			r.state.PositionMs = 0
			r.state.ServerTimeMs = nowMs
			r.state.IsPlaying = true
		}
	}

	r.state.Revision++

	broadcastPayload := protocol.PlaybackActionPayload{
		Action:       p.Action,
		Track:        r.state.CurrentTrack,
		PositionMs:   r.state.PositionMs,
		ServerTimeMs: r.state.ServerTimeMs,
		IsPlaying:    r.state.IsPlaying,
		Queue:        append([]protocol.TrackInfo(nil), r.state.Queue...),
		Revision:     r.state.Revision,
	}

	// Broadcast synchronously while holding room lock or right after
	clients := make([]*Client, 0, len(r.participants))
	for _, participant := range r.participants {
		if participant != nil {
			clients = append(clients, participant)
		}
	}

	for _, client := range clients {
		_ = client.Send(protocol.MsgTypeSyncPlayback, broadcastPayload)
	}
}

// HandleSuggestTrack allows guests to suggest songs to the room host.
func (r *Room) HandleSuggestTrack(c *Client, track *protocol.TrackInfo) {
	if track == nil || !protocol.SanitizeTrack(track) {
		c.SendError("invalid_track", "Suggested track must have valid ID and Title")
		return
	}

	r.mu.Lock()
	if r.host == nil || r.host.ID == c.ID {
		r.mu.Unlock()
		return
	}

	sugID := GenerateID("sug_")
	track.SuggestedBy = c.Name()
	payload := &protocol.SuggestionReceivedPayload{
		SuggestionID: sugID,
		FromUserID:   c.ID,
		FromUserName: c.Name(),
		Track:        track,
	}
	r.pendingSuggestions[sugID] = payload
	host := r.host
	r.mu.Unlock()

	if host != nil {
		_ = host.Send(protocol.MsgTypeSuggestionReceived, payload)
	}
}

// HandleApproveSuggestion allows the host to approve a suggested track into the queue.
func (r *Room) HandleApproveSuggestion(c *Client, suggestionID string) {
	r.mu.Lock()
	if r.host == nil || r.host.ID == c.ID {
		sug, exists := r.pendingSuggestions[suggestionID]
		if !exists || sug == nil {
			r.mu.Unlock()
			c.SendError("not_found", "Suggestion not found")
			return
		}
		delete(r.pendingSuggestions, suggestionID)
		r.state.Queue = append(r.state.Queue, *sug.Track)
		r.state.Revision++
		nowMs := time.Now().UnixMilli()

		broadcastPayload := protocol.PlaybackActionPayload{
			Action:       protocol.ActionQueueAdd,
			Track:        sug.Track,
			PositionMs:   r.state.PositionMs,
			ServerTimeMs: nowMs,
			IsPlaying:    r.state.IsPlaying,
			Queue:        append([]protocol.TrackInfo(nil), r.state.Queue...),
			Revision:     r.state.Revision,
		}
		r.mu.Unlock()

		r.Broadcast(protocol.MsgTypeSyncPlayback, broadcastPayload)
		return
	}
	r.mu.Unlock()
	c.SendError("unauthorized", "Only the host can approve suggestions")
}

// HandleRejectSuggestion allows the host to reject a proposed track.
func (r *Room) HandleRejectSuggestion(c *Client, suggestionID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.host == nil || r.host.ID != c.ID {
		c.SendError("unauthorized", "Only the host can reject suggestions")
		return
	}
	delete(r.pendingSuggestions, suggestionID)
}
