package main

import (
	"context"
	"flag"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Clash-Projects/lastwave-sync/internal/server"
	"github.com/Clash-Projects/lastwave-sync/internal/sync"
)

func main() {
	portFlag := flag.String("port", "", "Server HTTP/WebSocket port")
	flag.Parse()

	port := *portFlag
	if port == "" {
		port = os.Getenv("PORT")
	}
	if port == "" {
		port = "8080"
	}

	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))
	slog.SetDefault(logger)

	slog.Info("initializing LastWave Sync Server", "port", port)

	hub := sync.NewHub()
	srv := server.NewServer(server.Config{
		Port:            port,
		CleanupInterval: 2 * time.Minute,
	}, hub)

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		if err := srv.Start(); err != nil {
			slog.Error("server error", "err", err)
			os.Exit(1)
		}
	}()

	<-stopChan
	slog.Info("shutting down LastWave Sync Server...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown failed", "err", err)
	} else {
		slog.Info("server exited cleanly")
	}
}
