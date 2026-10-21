package server

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/Clash-Projects/lastwave-sync/internal/sync"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		// Allow all origins (native mobile apps and desktop clients)
		return true
	},
}

type Handler struct {
	hub       *sync.Hub
	startTime time.Time
}

func NewHandler(hub *sync.Hub) *Handler {
	return &Handler{
		hub:       hub,
		startTime: time.Now(),
	}
}

func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /health", h.handleHealth)
	mux.HandleFunc("GET /stats", h.handleStats)
	mux.HandleFunc("GET /ws", h.handleWebSocket)
}

func (h *Handler) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"service": "lastwave-sync",
		"status":  "healthy",
		"version": "1.0.0",
		"uptime":  time.Since(h.startTime).String(),
	})
}

func (h *Handler) handleStats(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"active_rooms":       h.hub.RoomCount(),
		"total_participants": h.hub.TotalParticipants(),
	})
}

func (h *Handler) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		slog.Warn("websocket upgrade failed", "remote_addr", r.RemoteAddr, "err", err)
		return
	}

	client := sync.NewClient(conn, h.hub)
	go client.WritePump()
	client.ReadPump()
}
