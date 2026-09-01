// Package honeycoin implements the HoneyCoin adapter — the launch
// reference implementation of provider.Provider (PRD D7 / FR-701 / FR-702,
// ARCHITECTURE §4.3). Every future adapter (M-Pesa, bank rails, EVM
// chains) copies this shape:
//
//   - a documented status-code mapping table ending in UNKNOWN,
//   - HMAC-signed requests with a per-request timeout,
//   - X-Idempotency-Key = our transaction key on state-changing calls,
//   - a circuit breaker (5 failures/30s → OPEN 60s → half-open probe) that
//     yields provider.ErrProviderUnavailable so the router can fail over,
//   - an adapter_calls audit row logged BEFORE every return (redacted),
//   - inbound callbacks verified via internal/callback.
package honeycoin

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/callback"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/health"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/store"
)

// ProviderName is the stable provider identifier.
const ProviderName = "honeycoin"

// DefaultTimeout is the per-request wire timeout (ARCHITECTURE §4.3:
// "timed out" calls). Every adapter call wraps its context with this; an
// earlier caller deadline wins.
const DefaultTimeout = 10 * time.Second

const maxResponseBody = 1 << 20 // 1 MiB response guard

// Wire endpoints (relative to HONEYCOIN_BASE_URL).
const (
	pathQuotes         = "/v1/quotes"
	pathTransfers      = "/v1/transfers"
	pathReconciliation = "/v1/reports/reconciliation"
)

// STATUS MAPPING TABLE — HoneyCoin wire status → internal TransferStatus.
//
//	HoneyCoin status   internal        meaning
//	───────────────────────────────────────────────────────────────────
//	PENDING            PENDING         accepted by rail, awaiting settlement
//	PROCESSING         PROCESSING      settlement in flight
//	CONFIRMED          SUCCEEDED       settled at destination
//	FAILED             FAILED          terminal failure, funds did not move
//	REVERSED           RETURNED        funds pulled back by reversal
//	RETURNED           RETURNED        returned by rail / destination
//	<anything else>    UNKNOWN         AMBIGUOUS — see below
//
// Unmapped (or empty) statuses map to StatusUnknown. Per SECURITY.md §4 an
// UNKNOWN result is ambiguous — money may or may not have moved — so the
// caller must PARK the transfer (fail-safe PROCESSING + ops alert) and must
// NEVER auto-retry the debit. Resolution happens via Poll, ReconcileReport
// or manual provider confirmation before any capture/compensation entry.
//
// NOTE: a wire "CANCELLED" status currently also maps to UNKNOWN (it is not
// in the launch status set); cancellation outcomes are driven by Cancel()
// responses and payments-level state instead. Follow-up: negotiate a
// CANCELLED status with HoneyCoin if callbacks ever report it.
func MapStatus(wire string) provider.TransferStatus {
	switch strings.ToUpper(strings.TrimSpace(wire)) {
	case "PENDING":
		return provider.StatusPending
	case "PROCESSING":
		return provider.StatusProcessing
	case "CONFIRMED":
		return provider.StatusSucceeded
	case "FAILED":
		return provider.StatusFailed
	case "REVERSED", "RETURNED":
		return provider.StatusReturned
	default:
		// Unmapped ⇒ ambiguous. Never guess, never fail the call — the
		// ambiguity is the state (park + alert upstream).
		return provider.StatusUnknown
	}
}

// WIRE ERROR CODE mapping (non-2xx responses):
//
//	unsupported_operation → provider.ErrUnsupported      (no breaker trip)
//	unsupported_currency   → provider.ErrUnsupportedCurrency (no trip)
//	not_found              → provider.ErrNotFound        (no breaker trip)
//	anything else          → opaque wire error           (5xx trips breaker)
func mapWireError(status int, body []byte) error {
	var we wireError
	_ = json.Unmarshal(body, &we)
	code := we.Error.Code
	msg := we.Error.Message
	switch code {
	case "unsupported_operation":
		return fmt.Errorf("honeycoin: rejected (%s): %w", msg, provider.ErrUnsupported)
	case "unsupported_currency":
		return fmt.Errorf("honeycoin: rejected (%s): %w", msg, provider.ErrUnsupportedCurrency)
	case "not_found":
		return fmt.Errorf("honeycoin: rejected (%s): %w", msg, provider.ErrNotFound)
	default:
		return fmt.Errorf("honeycoin: request rejected (HTTP %d, code %q): %s", status, code, msg)
	}
}

// Config configures the adapter. Zero-valued fields are filled from
// environment and defaults:
//
//	BaseURL        ← HONEYCOIN_BASE_URL        (required, no default)
//	SigningKey     ← HONEYCOIN_SIGNING_KEY     (required, no default)
//	CallbackSecret ← HONEYCOIN_CALLBACK_SECRET (fallback: HONEYCOIN_SIGNING_KEY)
//	Timeout        ← DefaultTimeout (10s per request)
//	HTTPClient     ← &http.Client{} (timeout enforced via context)
//	Verifier       ← NewVerifier(callbackSecret, NewMemoryReplayCache(), defaults)
//	Breaker        ← health.NewBreaker("honeycoin", defaults)
//	Audit          ← store.NewMemoryStore()
type Config struct {
	BaseURL        string
	SigningKey     []byte
	CallbackSecret []byte
	HTTPClient     *http.Client
	Timeout        time.Duration
	Verifier       *callback.Verifier
	Breaker        *health.Breaker
	Audit          store.AuditStore
}

// Adapter implements provider.Provider over the HoneyCoin REST wire.
type Adapter struct {
	baseURL    string
	signingKey []byte
	http       *http.Client
	timeout    time.Duration
	verifier   *callback.Verifier
	breaker    *health.Breaker
	audit      store.AuditStore
}

// New builds the adapter, resolving env/defaults for empty config fields.
func New(cfg Config) (*Adapter, error) {
	baseURL := strings.TrimRight(strings.TrimSpace(cfg.BaseURL), "/")
	if baseURL == "" {
		baseURL = strings.TrimRight(strings.TrimSpace(os.Getenv("HONEYCOIN_BASE_URL")), "/")
	}
	if baseURL == "" {
		return nil, errors.New("honeycoin: base URL not configured (Config.BaseURL or HONEYCOIN_BASE_URL)")
	}
	signingKey, err := ResolveSigningKey(cfg.SigningKey, "HONEYCOIN_SIGNING_KEY")
	if err != nil {
		return nil, err
	}
	callbackSecret := bytes.TrimSpace(cfg.CallbackSecret)
	if len(callbackSecret) == 0 {
		callbackSecret = bytes.TrimSpace([]byte(os.Getenv("HONEYCOIN_CALLBACK_SECRET")))
	}
	if len(callbackSecret) == 0 {
		callbackSecret = signingKey
	}
	verifier := cfg.Verifier
	if verifier == nil {
		verifier = callback.NewVerifier(callbackSecret, callback.NewMemoryReplayCache(), callback.VerifierConfig{})
	}
	breaker := cfg.Breaker
	if breaker == nil {
		breaker = health.NewBreaker(ProviderName, health.Config{})
	}
	audit := cfg.Audit
	if audit == nil {
		audit = store.NewMemoryStore()
	}
	httpc := cfg.HTTPClient
	if httpc == nil {
		httpc = &http.Client{}
	}
	timeout := cfg.Timeout
	if timeout <= 0 {
		timeout = DefaultTimeout
	}
	return &Adapter{
		baseURL:    baseURL,
		signingKey: signingKey,
		http:       httpc,
		timeout:    timeout,
		verifier:   verifier,
		breaker:    breaker,
		audit:      audit,
	}, nil
}

// Name implements Provider.
func (a *Adapter) Name() string { return ProviderName }

// Capabilities implements Provider. HoneyCoin serves the V1 wallet
// currency set (PRD D2) over its own rail, supports reversals, and settles
// USDC/USDT on EVM chains.
func (a *Adapter) Capabilities() provider.Capabilities {
	return provider.Capabilities{
		Currencies: []string{"KES", "USD", "EUR", "GBP", "USDC", "USDT"},
		Rails:      []string{ProviderName},
		Reversals:  true,
		OnChain:    true,
	}
}

// ---------------------------------------------------------------------------
// Wire DTOs (JSON field names are the HoneyCoin contract).
// ---------------------------------------------------------------------------

type wireQuoteReq struct {
	AmountMinor     int64  `json:"amount_minor"`
	Currency        string `json:"currency"`
	Exponent        int    `json:"exponent"`
	Rail            string `json:"rail"`
	DestinationType string `json:"destination_type"`
}

type wireQuoteResp struct {
	QuoteID      string    `json:"quote_id"`
	DebitMinor   int64     `json:"debit_minor"`
	FeeMinor     int64     `json:"fee_minor"`
	ReceiveMinor int64     `json:"receive_minor"`
	Currency     string    `json:"currency"`
	Exponent     int       `json:"exponent"`
	ExpiresAt    time.Time `json:"expires_at"`
}

type wireDestination struct {
	Type    string            `json:"type"`
	Details map[string]string `json:"details,omitempty"`
}

type wireTransferReq struct {
	AmountMinor int64             `json:"amount_minor"`
	Currency    string            `json:"currency"`
	Exponent    int               `json:"exponent"`
	Rail        string            `json:"rail"`
	Destination wireDestination   `json:"destination"`
	QuoteID     string            `json:"quote_id,omitempty"`
	Metadata    map[string]string `json:"metadata,omitempty"`
}

type wireTransferResp struct {
	ID          string `json:"id"`
	Status      string `json:"status"`
	AmountMinor int64  `json:"amount_minor"`
	Currency    string `json:"currency"`
	Exponent    int    `json:"exponent"`
	Reverses    string `json:"reverses,omitempty"`
}

type wireReversalReq struct {
	Reason string `json:"reason,omitempty"`
}

type wireReconReq struct {
	From time.Time `json:"from"`
	To   time.Time `json:"to"`
}

type wireReconLine struct {
	ID          string    `json:"id"`
	Status      string    `json:"status"`
	AmountMinor int64     `json:"amount_minor"`
	FeeMinor    int64     `json:"fee_minor"`
	Currency    string    `json:"currency"`
	Exponent    int       `json:"exponent"`
	OccurredAt  time.Time `json:"occurred_at"`
}

type wireReconResp struct {
	Lines []wireReconLine `json:"lines"`
}

type wireError struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

// wireCallback is the inbound callback envelope (HoneyCoin → us).
type wireCallback struct {
	Type        string `json:"type"`
	Ref         string `json:"ref"`
	Status      string `json:"status"`
	AmountMinor int64  `json:"amount_minor"`
	Currency    string `json:"currency"`
	Exponent    int    `json:"exponent"`
}

// ---------------------------------------------------------------------------
// Provider methods
// ---------------------------------------------------------------------------

// Quote implements Provider: prices a prospective transfer. Unsupported
// currencies are rejected locally (fast-fail, no wire call); wire-level
// rejections surface provider.ErrUnsupportedCurrency via the error-code
// mapping.
func (a *Adapter) Quote(ctx context.Context, r provider.QuoteRequest) (provider.Quote, error) {
	var zero provider.Quote
	if err := r.Amount.Validate(); err != nil {
		return zero, fmt.Errorf("honeycoin quote: %w", err)
	}
	if !a.Capabilities().SupportsCurrency(r.Amount.Currency) {
		return zero, fmt.Errorf("honeycoin quote: %s: %w", r.Amount.Currency, provider.ErrUnsupportedCurrency)
	}
	req := wireQuoteReq{
		AmountMinor:     r.Amount.AmountMinor,
		Currency:        r.Amount.Currency,
		Exponent:        r.Amount.Exponent,
		Rail:            r.Rail,
		DestinationType: r.Destination.Type,
	}
	var out wireQuoteResp
	if err := a.call(ctx, "Quote", http.MethodPost, pathQuotes, req, &out, "", ""); err != nil {
		return zero, err
	}
	return provider.Quote{
		ID:        out.QuoteID,
		Debit:     provider.Money{AmountMinor: out.DebitMinor, Currency: out.Currency, Exponent: out.Exponent},
		Receive:   provider.Money{AmountMinor: out.ReceiveMinor, Currency: out.Currency, Exponent: out.Exponent},
		Fee:       provider.Money{AmountMinor: out.FeeMinor, Currency: out.Currency, Exponent: out.Exponent},
		ExpiresAt: out.ExpiresAt,
	}, nil
}

// Initiate implements Provider. r.TransactionKey is REQUIRED (adapter-level
// idempotency, SECURITY §4) and is sent as X-Idempotency-Key so a retry of
// the same transaction key returns the original transfer instead of
// double-charging.
func (a *Adapter) Initiate(ctx context.Context, r provider.InitiateRequest) (provider.ProviderRef, error) {
	var zero provider.ProviderRef
	if r.TransactionKey == "" {
		return zero, errors.New("honeycoin initiate: TransactionKey is required (adapter-level idempotency)")
	}
	if err := r.Amount.Validate(); err != nil {
		return zero, fmt.Errorf("honeycoin initiate: %w", err)
	}
	if !a.Capabilities().SupportsCurrency(r.Amount.Currency) {
		return zero, fmt.Errorf("honeycoin initiate: %s: %w", r.Amount.Currency, provider.ErrUnsupportedCurrency)
	}
	rail := r.Rail
	if rail == "" {
		rail = ProviderName
	}
	if !a.Capabilities().SupportsRail(rail) {
		return zero, fmt.Errorf("honeycoin initiate: rail %s: %w", rail, provider.ErrUnsupported)
	}
	req := wireTransferReq{
		AmountMinor: r.Amount.AmountMinor,
		Currency:    r.Amount.Currency,
		Exponent:    r.Amount.Exponent,
		Rail:        rail,
		Destination: wireDestination{Type: r.Destination.Type, Details: r.Destination.Details},
		QuoteID:     r.QuoteID,
		Metadata:    r.Metadata,
	}
	var out wireTransferResp
	if err := a.call(ctx, "Initiate", http.MethodPost, pathTransfers, req, &out, r.TransactionKey, ""); err != nil {
		return zero, err
	}
	if out.ID == "" {
		// Malformed success: no transfer id. Fail loudly — treat as
		// protocol violation (outcome failure in the audit trail).
		return zero, errors.New("honeycoin initiate: provider returned an empty transfer id")
	}
	return provider.ProviderRef{Provider: ProviderName, Ref: out.ID}, nil
}

// Poll implements Provider: maps the current wire status through the table
// above. Unmapped statuses return (StatusUnknown, nil) — park + alert
// upstream, never auto-retry (see the AMBIGUITY CONTRACT in
// internal/provider).
func (a *Adapter) Poll(ctx context.Context, ref provider.ProviderRef) (provider.TransferStatus, error) {
	if err := a.checkRef(ref); err != nil {
		return "", fmt.Errorf("honeycoin poll: %w", err)
	}
	var out wireTransferResp
	path := pathTransfers + "/" + ref.Ref
	if err := a.call(ctx, "Poll", http.MethodGet, path, nil, &out, "", ref.Ref); err != nil {
		return "", err
	}
	return MapStatus(out.Status), nil
}

// HandleCallback implements Provider: verifies the callback
// (signature → freshness → replay, internal/callback) and maps the status
// from the verified body. The status is meaningless on error — callers act
// on the error sentinels (ErrBadSignature / ErrStale / ErrReplay) and drop
// or alert. Callbacks are INBOUND (no wire traffic), so the breaker is not
// consulted; the verification result is still audited.
func (a *Adapter) HandleCallback(ctx context.Context, cb provider.Callback) (provider.TransferStatus, error) {
	rec := provider.AdapterCall{
		ID:         uuid.NewString(),
		Provider:   ProviderName,
		Method:     "HandleCallback",
		HTTPMethod: http.MethodPost,
		Path:       "(inbound callback)",
		StartedAt:  time.Now(),
		Request:    redact(cb.Body),
	}
	if cb.Provider != ProviderName {
		return "", a.finish(rec, fmt.Errorf("honeycoin callback: routed to wrong adapter (provider %q)", cb.Provider), "")
	}
	res, verr := a.verifier.Verify(cb)
	if verr != nil {
		// Security events: forged/stale/replayed callbacks are detected
		// here — SECURITY §6 requires alerting on these.
		return "", a.finish(rec, verr, "")
	}
	if res != callback.Verified {
		return "", a.finish(rec, fmt.Errorf("honeycoin callback: %w", callback.ErrMalformed), "")
	}
	var wcb wireCallback
	if err := json.Unmarshal(cb.Body, &wcb); err != nil {
		return "", a.finish(rec, fmt.Errorf("honeycoin callback: decode body: %w", err), "")
	}
	status := MapStatus(wcb.Status)
	return status, a.finish(rec, nil, string(status))
}

// Cancel implements Provider against POST /v1/transfers/{ref}/cancel. The
// idempotency key is derived from the ref ("cancel:"+ref) per SECURITY §4
// (adapter keys derive from the request key chain).
func (a *Adapter) Cancel(ctx context.Context, ref provider.ProviderRef) error {
	if err := a.checkRef(ref); err != nil {
		return fmt.Errorf("honeycoin cancel: %w", err)
	}
	path := pathTransfers + "/" + ref.Ref + "/cancel"
	return a.call(ctx, "Cancel", http.MethodPost, path, wireReversalReq{Reason: "canceled by SharkPay"}, nil, "cancel:"+ref.Ref, ref.Ref)
}

// Reverse implements Provider against POST /v1/transfers/{ref}/reverse,
// returning the reversal transfer's ref. Gated on the Reversals capability:
// providers without it must return ErrUnsupported without wire traffic.
func (a *Adapter) Reverse(ctx context.Context, ref provider.ProviderRef) (provider.ProviderRef, error) {
	var zero provider.ProviderRef
	if err := a.checkRef(ref); err != nil {
		return zero, fmt.Errorf("honeycoin reverse: %w", err)
	}
	if !a.Capabilities().Reversals {
		return zero, fmt.Errorf("honeycoin reverse: %w", provider.ErrUnsupported)
	}
	path := pathTransfers + "/" + ref.Ref + "/reverse"
	var out wireTransferResp
	err := a.call(ctx, "Reverse", http.MethodPost, path, wireReversalReq{}, &out, "reverse:"+ref.Ref, ref.Ref)
	if err != nil {
		return zero, err
	}
	if out.ID == "" {
		return zero, errors.New("honeycoin reverse: provider returned an empty reversal id")
	}
	return provider.ProviderRef{Provider: ProviderName, Ref: out.ID}, nil
}

// ReconcileReport implements Provider against
// POST /v1/reports/reconciliation. Lines are mapped through the status
// table; unmapped provider statuses surface as StatusUnknown lines so the
// reconciliation service can flag them as breaks instead of guessing.
func (a *Adapter) ReconcileReport(ctx context.Context, window provider.Window) ([]provider.ProviderLine, error) {
	if err := window.Validate(); err != nil {
		return nil, fmt.Errorf("honeycoin reconcile: %w", err)
	}
	var out wireReconResp
	err := a.call(ctx, "ReconcileReport", http.MethodPost, pathReconciliation, wireReconReq{From: window.From, To: window.To}, &out, "", "")
	if err != nil {
		return nil, err
	}
	lines := make([]provider.ProviderLine, 0, len(out.Lines))
	for _, l := range out.Lines {
		lines = append(lines, provider.ProviderLine{
			Ref:        l.ID,
			Status:     MapStatus(l.Status),
			Amount:     provider.Money{AmountMinor: l.AmountMinor, Currency: l.Currency, Exponent: l.Exponent},
			Fee:        provider.Money{AmountMinor: l.FeeMinor, Currency: l.Currency, Exponent: l.Exponent},
			OccurredAt: l.OccurredAt,
		})
	}
	return lines, nil
}

// checkRef validates a ProviderRef for this adapter. Refs are restricted to
// a URL-safe charset so the signed path always equals the wire path.
func (a *Adapter) checkRef(ref provider.ProviderRef) error {
	if ref.Ref == "" {
		return errors.New("empty provider ref")
	}
	if ref.Provider != "" && ref.Provider != ProviderName {
		return fmt.Errorf("ref belongs to provider %q, not %q", ref.Provider, ProviderName)
	}
	for i := 0; i < len(ref.Ref); i++ {
		c := ref.Ref[i]
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9', c == '-', c == '_', c == '.':
		default:
			return fmt.Errorf("provider ref %q contains characters outside the URL-safe signing charset", ref.Ref)
		}
	}
	return nil
}

// ---------------------------------------------------------------------------
// Wire call core: signing, timeout, breaker, audit.
// ---------------------------------------------------------------------------

// call executes one signed wire call. Every provider method funnels through
// it so that breaker guarding, per-request timeout, and the
// audit-before-return invariant are structural (SECURITY §1 repudiation,
// §4 idempotency/timeout/breaker).
//
// Failure classification:
//
//	transport error / timeout / HTTP 5xx → breaker RecordFailure
//	HTTP 4xx (business rejection)         → NO breaker trip (provider alive)
//	open breaker                          → provider.ErrProviderUnavailable, no wire call
//	timeout                               → error wrapping context.DeadlineExceeded
func (a *Adapter) call(ctx context.Context, op, method, path string, reqBody, out any, idemKey, ref string) (err error) {
	rec := provider.AdapterCall{
		ID:         uuid.NewString(),
		Provider:   ProviderName,
		Method:     op,
		HTTPMethod: method,
		Path:       path,
		Ref:        ref,
		StartedAt:  time.Now(),
	}
	defer func() {
		// Audit BEFORE the caller sees the result. A failing audit store
		// fails the call closed (the audit trail is mandatory for money
		// movement, NFR-06); the in-memory store never fails.
		if rerr := a.finish(rec, err, rec.Response); rerr != nil && err == nil {
			err = rerr
		}
	}()

	if berr := a.breaker.Allow(); berr != nil {
		// Breaker open: fail fast so the router can fail over. Still
		// audited (outcome unavailable) — repudiation control.
		return fmt.Errorf("honeycoin %s: %w", op, provider.ErrProviderUnavailable)
	}

	var body []byte
	if reqBody != nil {
		body, err = json.Marshal(reqBody)
		if err != nil {
			return fmt.Errorf("honeycoin %s: encode request: %w", op, err)
		}
	}
	rec.Request = redact(body)

	httpReq, err := http.NewRequestWithContext(ctx, method, a.baseURL+path, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("honeycoin %s: build request: %w", op, err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	if idemKey != "" {
		httpReq.Header.Set(IdempotencyHeader, idemKey)
	}
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	httpReq.Header.Set(headerTimestamp, ts)
	httpReq.Header.Set(headerSignature, SignRequest(a.signingKey, ts, method, path, body))

	// Per-request timeout (default 10s); an earlier caller deadline wins.
	callCtx, cancel := context.WithTimeout(ctx, a.timeout)
	defer cancel()
	httpReq = httpReq.WithContext(callCtx)

	resp, err := a.http.Do(httpReq)
	if err != nil {
		a.breaker.RecordFailure()
		cerr := callCtx.Err()
		if errors.Is(cerr, context.DeadlineExceeded) || errors.Is(err, context.DeadlineExceeded) {
			return fmt.Errorf("honeycoin %s: timed out after %s: %w", op, a.timeout, context.DeadlineExceeded)
		}
		if errors.Is(cerr, context.Canceled) || errors.Is(err, context.Canceled) {
			return fmt.Errorf("honeycoin %s: canceled: %w", op, context.Canceled)
		}
		return fmt.Errorf("honeycoin %s: transport error: %w", op, err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBody))
	if err != nil {
		a.breaker.RecordFailure()
		return fmt.Errorf("honeycoin %s: read response: %w", op, err)
	}
	rec.StatusCode = resp.StatusCode
	rec.Response = redact(raw)

	if resp.StatusCode >= 500 {
		a.breaker.RecordFailure()
		return fmt.Errorf("honeycoin %s: %w", op, mapWireError(resp.StatusCode, raw))
	}
	if resp.StatusCode >= 400 {
		// Business rejection: provider is alive — do NOT trip the breaker.
		return fmt.Errorf("honeycoin %s: %w", op, mapWireError(resp.StatusCode, raw))
	}
	a.breaker.RecordSuccess()

	if out != nil {
		if err := json.Unmarshal(raw, out); err != nil {
			return fmt.Errorf("honeycoin %s: decode response: %w", op, err)
		}
	}
	return nil
}

// finish completes an audit record (latency, outcome, optional response
// summary) and appends it. It returns an error only if the audit store
// fails (fail-closed signal for the caller).
func (a *Adapter) finish(rec provider.AdapterCall, err error, response string) error {
	rec.Latency = time.Since(rec.StartedAt)
	rec.Outcome = outcomeFor(err)
	if response != "" {
		rec.Response = response
	}
	// The BUSINESS error always reaches the caller; a failing audit store is
	// joined so it is never silently dropped (NFR-06). Note the in-memory
	// store never fails — this matters for the future Postgres/Redis audit
	// stores. A wire-success + audit-failure surfaces as an error so the
	// caller parks the transfer instead of proceeding unaudited.
	if aerr := a.audit.Append(rec); aerr != nil {
		return errors.Join(err, fmt.Errorf("honeycoin: audit append failed: %w", aerr))
	}
	return err
}

// outcomeFor classifies a call error into the audit outcome vocabulary.
func outcomeFor(err error) string {
	switch {
	case err == nil:
		return provider.OutcomeSuccess
	case errors.Is(err, provider.ErrProviderUnavailable):
		return provider.OutcomeUnavailable
	default:
		return provider.OutcomeFailure
	}
}

// redactJSONKeySet lists body fields whose values are stripped before the
// body enters the audit trail (defense in depth: the HoneyCoin protocol
// carries no secrets in bodies; headers are never audited at all).
var redactJSONKeys = map[string]bool{
	"signature":     true,
	"signing_key":   true,
	"secret":        true,
	"authorization": true,
	"token":         true,
	"api_key":       true,
	"password":      true,
}

// redact returns a printable, secret-scrubbed rendering of a wire body.
func redact(body []byte) string {
	if len(body) == 0 {
		return ""
	}
	var doc any
	if err := json.Unmarshal(body, &doc); err != nil {
		return "<unparseable body>"
	}
	out, err := json.Marshal(redactValue(doc))
	if err != nil {
		return "<unrenderable body>"
	}
	return string(out)
}

func redactValue(v any) any {
	switch t := v.(type) {
	case map[string]any:
		for k, val := range t {
			if redactJSONKeys[strings.ToLower(k)] {
				t[k] = "[REDACTED]"
			} else {
				t[k] = redactValue(val)
			}
		}
		return t
	case []any:
		for i, val := range t {
			t[i] = redactValue(val)
		}
		return t
	default:
		return v
	}
}
