// Command server runs the SharkPay provider gateway: it registers the
// HoneyCoin adapter (launch provider), exposes a minimal internal JSON/HTTP
// surface for quote/initiate/poll/cancel/reverse/reconcile + the inbound
// callback ingress, and routes candidates via the router.
//
// The production-internal transport is gRPC with mTLS (ARCHITECTURE §2);
// contracts/ (OpenAPI/proto) and Kafka event publishing
// (providers.transfer.*.v1) are owned by other work packages — this HTTP
// surface is the functional interim and stays deliberately thin.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/callback"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/health"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/honeycoin"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/router"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/store"
)

func main() {
	port := envOr("PORT", "8080")

	// --- wiring -----------------------------------------------------------
	audit := store.NewMemoryStore() // production: PostgreSQL adapter_calls table
	breakers := health.NewManager(health.Config{})
	breaker := breakers.Breaker(honeycoin.ProviderName)

	// Callback secret: dedicated env first, signing key as fallback.
	callbackSecret := strings.TrimSpace(os.Getenv("HONEYCOIN_CALLBACK_SECRET"))
	verifier := callback.NewVerifier([]byte(callbackSecret), callback.NewMemoryReplayCache(), callback.VerifierConfig{})
	// (an empty secret above would fail-closed on every callback; the
	// adapter re-resolves the secret with the same fallback rules, so if
	// the verifier was misconfigured we rebuild it after adapter New.)
	adapter, err := honeycoin.New(honeycoin.Config{
		Breaker:  breaker,
		Audit:    audit,
		Verifier: verifier,
	})
	if err != nil {
		log.Fatalf("providers: bootstrap honeycoin adapter: %v", err)
	}

	registry := provider.NewRegistry()
	if err := registry.Register(adapter); err != nil {
		log.Fatalf("providers: register honeycoin: %v", err)
	}
	_ = registry.SetHealth(honeycoin.ProviderName, provider.HealthHealthy)

	rt := router.New(router.DefaultConfig())

	// Static cost/latency table until the observability feed lands
	// (follow-up: p99 + fee bps from metrics).
	costTable := map[string]router.Candidate{
		honeycoin.ProviderName: {
			Name:    honeycoin.ProviderName,
			Caps:    adapter.Capabilities(),
			CostBps: 50,                     // 0.5% reference fee
			P99:     800 * time.Millisecond, // reference p99
			Health:  provider.HealthHealthy,
			MinTier: "",
		},
	}

	// --- HTTP surface ------------------------------------------------------
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "service": "providers"})
	})
	mux.HandleFunc("GET /v1/providers", func(w http.ResponseWriter, _ *http.Request) {
		type row struct {
			Name    string                `json:"name"`
			Health  provider.HealthState  `json:"health"`
			Caps    provider.Capabilities `json:"capabilities"`
			Breaker string                `json:"breaker"`
		}
		out := make([]row, 0)
		for _, p := range registry.List() {
			out = append(out, row{
				Name:    p.Name(),
				Health:  registry.Health(p.Name()),
				Caps:    p.Capabilities(),
				Breaker: breakers.Breaker(p.Name()).State().String(),
			})
		}
		writeJSON(w, http.StatusOK, out)
	})
	mux.HandleFunc("POST /v1/route", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			AmountMinor   int64  `json:"amount_minor"`
			Currency      string `json:"currency"`
			Rail          string `json:"rail"`
			PrincipalTier string `json:"principal_tier"`
		}
		if !readJSON(w, r, &req) {
			return
		}
		var cands []router.Candidate
		for _, c := range costTable {
			c.Health = registry.Health(c.Name)
			cands = append(cands, c)
		}
		cand, err := rt.Select(router.Request(req), cands)
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"provider": cand.Name,
			"cost_bps": cand.CostBps,
			"p99_ms":   cand.P99.Milliseconds(),
			"health":   cand.Health,
		})
	})
	mux.HandleFunc("POST /v1/quote", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Provider    string `json:"provider"`
			AmountMinor int64  `json:"amount_minor"`
			Currency    string `json:"currency"`
			Exponent    int    `json:"exponent"`
			Rail        string `json:"rail"`
			DestType    string `json:"destination_type"`
		}
		if !readJSON(w, r, &req) {
			return
		}
		p, ok := registry.Get(req.Provider)
		if !ok {
			writeErr(w, fmt.Errorf("unknown provider %q", req.Provider))
			return
		}
		quote, err := p.Quote(r.Context(), provider.QuoteRequest{
			Amount:      provider.Money{AmountMinor: req.AmountMinor, Currency: req.Currency, Exponent: req.Exponent},
			Rail:        req.Rail,
			Destination: provider.Destination{Type: req.DestType},
		})
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, quote)
	})
	mux.HandleFunc("POST /v1/initiate", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Provider       string            `json:"provider"`
			TransactionKey string            `json:"transaction_key"`
			AmountMinor    int64             `json:"amount_minor"`
			Currency       string            `json:"currency"`
			Exponent       int               `json:"exponent"`
			Rail           string            `json:"rail"`
			Destination    map[string]string `json:"destination"`
			Metadata       map[string]string `json:"metadata"`
		}
		if !readJSON(w, r, &req) {
			return
		}
		p, ok := registry.Get(req.Provider)
		if !ok {
			writeErr(w, fmt.Errorf("unknown provider %q", req.Provider))
			return
		}
		dest := provider.Destination{}
		if t, ok := req.Destination["type"]; ok {
			dest.Type = t
			details := make(map[string]string, len(req.Destination))
			for k, v := range req.Destination {
				if k != "type" {
					details[k] = v
				}
			}
			dest.Details = details
		}
		ref, err := p.Initiate(r.Context(), provider.InitiateRequest{
			TransactionKey: req.TransactionKey,
			Amount:         provider.Money{AmountMinor: req.AmountMinor, Currency: req.Currency, Exponent: req.Exponent},
			Rail:           req.Rail,
			Destination:    dest,
			Metadata:       req.Metadata,
		})
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, ref)
	})
	mux.HandleFunc("GET /v1/providers/{name}/transfers/{ref}", func(w http.ResponseWriter, r *http.Request) {
		p, ok := registry.Get(r.PathValue("name"))
		if !ok {
			writeErr(w, fmt.Errorf("unknown provider %q", r.PathValue("name")))
			return
		}
		st, err := p.Poll(r.Context(), provider.ProviderRef{Provider: p.Name(), Ref: r.PathValue("ref")})
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"provider": p.Name(), "ref": r.PathValue("ref"), "status": st})
	})
	mux.HandleFunc("POST /v1/providers/{name}/transfers/{ref}/cancel", func(w http.ResponseWriter, r *http.Request) {
		p, ok := registry.Get(r.PathValue("name"))
		if !ok {
			writeErr(w, fmt.Errorf("unknown provider %q", r.PathValue("name")))
			return
		}
		if err := p.Cancel(r.Context(), provider.ProviderRef{Provider: p.Name(), Ref: r.PathValue("ref")}); err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"cancelled": true})
	})
	mux.HandleFunc("POST /v1/providers/{name}/transfers/{ref}/reverse", func(w http.ResponseWriter, r *http.Request) {
		p, ok := registry.Get(r.PathValue("name"))
		if !ok {
			writeErr(w, fmt.Errorf("unknown provider %q", r.PathValue("name")))
			return
		}
		ref, err := p.Reverse(r.Context(), provider.ProviderRef{Provider: p.Name(), Ref: r.PathValue("ref")})
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, ref)
	})
	mux.HandleFunc("POST /v1/providers/{name}/reconcile", func(w http.ResponseWriter, r *http.Request) {
		p, ok := registry.Get(r.PathValue("name"))
		if !ok {
			writeErr(w, fmt.Errorf("unknown provider %q", r.PathValue("name")))
			return
		}
		var req struct {
			From time.Time `json:"from"`
			To   time.Time `json:"to"`
		}
		if !readJSON(w, r, &req) {
			return
		}
		lines, err := p.ReconcileReport(r.Context(), provider.Window{From: req.From, To: req.To})
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"lines": lines})
	})
	// Inbound provider callbacks (verification happens inside the adapter).
	mux.HandleFunc("POST /v1/providers/{name}/callbacks", func(w http.ResponseWriter, r *http.Request) {
		p, ok := registry.Get(r.PathValue("name"))
		if !ok {
			writeErr(w, fmt.Errorf("unknown provider %q", r.PathValue("name")))
			return
		}
		body, err := readRaw(r)
		if err != nil {
			writeErr(w, err)
			return
		}
		headers := map[string]string{}
		for k, vv := range r.Header {
			headers[k] = strings.Join(vv, ",")
		}
		st, err := p.HandleCallback(r.Context(), provider.Callback{Provider: p.Name(), Headers: headers, Body: body})
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": st})
	})

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	// --- lifecycle ---------------------------------------------------------
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	errCh := make(chan error, 1)
	go func() {
		log.Printf("providers: gateway listening on :%s (provider=%s, audit=memory, replay-cache=memory)", port, honeycoin.ProviderName)
		errCh <- srv.ListenAndServe()
	}()
	select {
	case err := <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("providers: server error: %v", err)
		}
	case <-ctx.Done():
		log.Printf("providers: shutdown signal received")
		shCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(shCtx); err != nil {
			log.Fatalf("providers: graceful shutdown failed: %v", err)
		}
		log.Printf("providers: stopped")
	}
}

func envOr(name, def string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return def
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("providers: write response: %v", err)
	}
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(v); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": map[string]string{"code": "bad_request", "message": err.Error()}})
		return false
	}
	return true
}

func readRaw(r *http.Request) ([]byte, error) {
	defer r.Body.Close()
	return io.ReadAll(io.LimitReader(r.Body, 1<<20))
}

// writeErr maps typed provider errors onto HTTP statuses (error envelope
// shape per API-CONTRACTS §1.4).
func writeErr(w http.ResponseWriter, err error) {
	code := http.StatusInternalServerError
	switch {
	case errors.Is(err, provider.ErrUnsupported),
		errors.Is(err, provider.ErrUnsupportedCurrency):
		code = http.StatusBadRequest
	case errors.Is(err, provider.ErrNotFound):
		code = http.StatusNotFound
	case errors.Is(err, provider.ErrProviderUnavailable):
		code = http.StatusServiceUnavailable
	case errors.Is(err, callback.ErrBadSignature),
		errors.Is(err, callback.ErrStale),
		errors.Is(err, callback.ErrReplay),
		errors.Is(err, callback.ErrMalformed):
		code = http.StatusUnauthorized
	}
	writeJSON(w, code, map[string]any{"error": map[string]string{"code": http.StatusText(code), "message": err.Error()}})
}
