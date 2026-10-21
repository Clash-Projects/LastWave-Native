package server

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/Clash-Projects/lastwave-jam/internal/sync"
)

// Config configures the server runtime parameters.
type Config struct {
	Port            string
	CleanupInterval time.Duration
}

// Server encapsulates the HTTP server, WebSocket handler, and synchronization hub.
type Server struct {
	cfg        Config
	hub        *sync.Hub
	httpServer *http.Server
	cancelFunc context.CancelFunc
}

// NewServer initializes a new Server instance.
func NewServer(cfg Config, hub *sync.Hub) *Server {
	if cfg.Port == "" {
		cfg.Port = "8080"
	}
	if cfg.CleanupInterval <= 0 {
		cfg.CleanupInterval = 1 * time.Minute
	}

	mux := http.NewServeMux()
	handler := NewHandler(hub)
	handler.RegisterRoutes(mux)

	return &Server{
		cfg: cfg,
		hub: hub,
		httpServer: &http.Server{
			Addr:         ":" + cfg.Port,
			Handler:      mux,
			ReadTimeout:  15 * time.Second,
			WriteTimeout: 15 * time.Second,
			IdleTimeout:  60 * time.Second,
		},
	}
}

// Start launches the background room cleanup loop and starts listening for HTTP/WS requests.
func (s *Server) Start() error {
	ctx, cancel := context.WithCancel(context.Background())
	s.cancelFunc = cancel

	go s.hub.RunCleanupLoop(ctx, s.cfg.CleanupInterval)

	slog.Info("LastWave Sync Server listening", "addr", s.httpServer.Addr)
	if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		return fmt.Errorf("http server failure: %w", err)
	}
	return nil
}

// Shutdown gracefully terminates HTTP connections and stops background loops.
func (s *Server) Shutdown(ctx context.Context) error {
	if s.cancelFunc != nil {
		s.cancelFunc()
	}
	return s.httpServer.Shutdown(ctx)
}
