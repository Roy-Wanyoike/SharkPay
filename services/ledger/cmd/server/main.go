// Command server runs the SharkPay ledger service — the double-entry source
// of truth for all money movement. It exposes the internal JSON API on
// LISTEN_ADDR and shuts down gracefully on SIGINT/SIGTERM.
//
// Environment:
//
//	LISTEN_ADDR        listen address                (default ":8080")
//	DATABASE_URL       postgres DSN, required for postgres store
//	LEDGER_STORE       "postgres" (default) | "memory" (dev-only fake)
//	INTERNAL_API_TOKEN optional shared bearer token for the internal API
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/api"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/service"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/storage"
)

func main() {
	if err := run(); err != nil {
		slog.Error("ledger service exited with error", "error", err)
		os.Exit(1)
	}
}

type config struct {
	ListenAddr    string
	DatabaseURL   string
	Store         string
	InternalToken string
}

func configFromEnv() (config, error) {
	cfg := config{
		ListenAddr:    envOr("LISTEN_ADDR", ":8080"),
		DatabaseURL:   os.Getenv("DATABASE_URL"),
		Store:         envOr("LEDGER_STORE", "postgres"),
		InternalToken: os.Getenv("INTERNAL_API_TOKEN"),
	}
	switch cfg.Store {
	case "postgres":
		if cfg.DatabaseURL == "" {
			return config{}, errors.New("DATABASE_URL is required (or set LEDGER_STORE=memory for local development)")
		}
	case "memory":
		// dev-only escape hatch: run on the in-memory fake store
	default:
		return config{}, fmt.Errorf("unknown LEDGER_STORE %q (want postgres or memory)", cfg.Store)
	}
	return cfg, nil
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg, err := configFromEnv()
	if err != nil {
		return err
	}

	var store domain.Store
	switch cfg.Store {
	case "memory":
		slog.Warn("LEDGER_STORE=memory: in-memory fake store — development only, NEVER production")
		store = storage.NewFakeStore()
	default:
		store, err = storage.NewPostgresStore(ctx, cfg.DatabaseURL)
		if err != nil {
			return fmt.Errorf("connect ledger store: %w", err)
		}
	}
	defer store.Close()

	svc := service.New(store)
	handler := api.New(svc, cfg.InternalToken)

	srv := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		slog.Info("ledger service listening", "addr", cfg.ListenAddr, "store", cfg.Store)
		errCh <- srv.ListenAndServe()
	}()

	select {
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		slog.Info("shutdown signal received, draining connections")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			return fmt.Errorf("graceful shutdown: %w", err)
		}
		slog.Info("ledger service stopped cleanly")
		return nil
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
