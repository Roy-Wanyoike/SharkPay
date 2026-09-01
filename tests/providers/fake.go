// Package conformance is the SharkPay provider conformance suite (WP-4,
// PRD FR-702, docs/ROADMAP.md Phase 4 exit criteria).
//
// It drives the REAL HoneyCoin adapter (services/providers, via the public
// re-export surface github.com/Roy-Wanyoike/SharkPay/services/providers)
// over HTTP against an in-process fake HoneyCoin server that speaks the
// wire contract pinned by tests/wiremock/mappings/*.json:
//
//	POST /v1/transfers            → 202 {id,status,currency,amount_minor}
//	GET  /v1/transfers/{ref}      → 200 {id,status,currency,amount_minor}
//	POST /v1/quotes               → 200 quote DTO
//	POST /v1/transfers/{ref}/cancel  → 200 transfer DTO
//	POST /v1/transfers/{ref}/reverse → 200 transfer DTO (+reverses)
//	POST /v1/reports/reconciliation → 200 {lines:[...]}
//
// The fake is deliberately INDEPENDENT of the adapter's own signing code:
// request signatures (X-HoneyCoin-*) and callback signatures (X-SharkPay-*)
// are computed and verified with local crypto/hmac implementations, so a
// bug or silent contract change in services/providers cannot make this
// suite lie. The SUT's exported constants are additionally pinned to the
// documented header names by a dedicated harness test.
//
// Failure-injection knobs (ServerConfig / Set* methods) let every scenario
// dial in a specific wire misbehavior: latency, injected HTTP status codes,
// malformed bodies, signing-key mismatch, empty transfer ids, stale or
// forged callbacks, replayed callback bodies and partial (mixed-status)
// reconciliation reports.
//
// Money safety: the fake itself uses integer arithmetic only — amounts are
// int64 minor units end to end, never floats (DATA-MODEL §1).
package conformance

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers"
)

// Wire endpoints — the path set of tests/wiremock/mappings/*.json plus the
// adapter's remaining documented endpoints.
const (
	PathQuotes         = "/v1/quotes"
	PathTransfers      = "/v1/transfers"
	PathReconciliation = "/v1/reports/reconciliation"
)

// Outbound request headers the real HoneyCoin expects (wire contract).
const (
	HeaderRequestTimestamp = "X-HoneyCoin-Timestamp"
	HeaderRequestSignature = "X-HoneyCoin-Signature"
	HeaderIdempotencyKey   = "X-Idempotency-Key"
)

// Inbound callback headers (internal/callback scheme).
const (
	HeaderCallbackTimestamp = "X-SharkPay-Timestamp"
	HeaderCallbackSignature = "X-SharkPay-Signature"
)

// Shared secrets used by the suite's default environment. The fake signs
// callbacks with CallbackSecret and verifies requests with SigningKey; the
// adapter under test is configured with the same values (mismatch scenarios
// override one side only).
const (
	DefaultSigningKey     = "honeycoin-conformance-signing-key"
	DefaultCallbackSecret = "honeycoin-conformance-callback-secret"
)

// Canned wire values — byte-for-byte the values pinned by
// tests/wiremock/mappings/honeycoin-initiate-transfer.json and
// honeycoin-transfer-status.json, so the fake and the dev/CI WireMock stubs
// cannot drift (enforced by the TestWireContractDrift scenarios).
const (
	CannedTransferID   = "hct_stub_000001"
	CannedInitStatus   = "PENDING"
	CannedPollStatus   = "CONFIRMED"
	CannedCurrency     = "KES"
	CannedAmountMinor  = int64(150000)
	CannedFeeFlatMinor = int64(25)
)

// ---------------------------------------------------------------------------
// Wire DTOs — field names are the HoneyCoin contract (mirror of the
// adapter's wire DTOs; deliberately re-declared locally so a rename in the
// adapter that breaks the wire contract fails here).
// ---------------------------------------------------------------------------

type quoteRequest struct {
	AmountMinor     int64  `json:"amount_minor"`
	Currency        string `json:"currency"`
	Exponent        int    `json:"exponent"`
	Rail            string `json:"rail"`
	DestinationType string `json:"destination_type"`
}

type quoteResponse struct {
	QuoteID      string    `json:"quote_id"`
	DebitMinor   int64     `json:"debit_minor"`
	FeeMinor     int64     `json:"fee_minor"`
	ReceiveMinor int64     `json:"receive_minor"`
	Currency     string    `json:"currency"`
	Exponent     int       `json:"exponent"`
	ExpiresAt    time.Time `json:"expires_at"`
}

type transferRequest struct {
	AmountMinor int64               `json:"amount_minor"`
	Currency    string              `json:"currency"`
	Exponent    int                 `json:"exponent"`
	Rail        string              `json:"rail"`
	Destination transferDestination `json:"destination"`
	QuoteID     string              `json:"quote_id,omitempty"`
	Metadata    map[string]string   `json:"metadata,omitempty"`
}

type transferDestination struct {
	Type    string            `json:"type"`
	Details map[string]string `json:"details,omitempty"`
}

// transferResponse matches the WireMock mapping bodies exactly:
// {id,status,currency,amount_minor} (+reverses on reversal transfers).
type transferResponse struct {
	ID          string `json:"id,omitempty"`
	Status      string `json:"status"`
	Currency    string `json:"currency"`
	AmountMinor int64  `json:"amount_minor"`
	Reverses    string `json:"reverses,omitempty"`
}

type reconRequest struct {
	From time.Time `json:"from"`
	To   time.Time `json:"to"`
}

type reconLine struct {
	ID          string    `json:"id"`
	Status      string    `json:"status"`
	AmountMinor int64     `json:"amount_minor"`
	FeeMinor    int64     `json:"fee_minor"`
	Currency    string    `json:"currency"`
	Exponent    int       `json:"exponent"`
	OccurredAt  time.Time `json:"occurred_at"`
}

type reconResponse struct {
	Lines []reconLine `json:"lines"`
}

type wireErrorResp struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func newWireError(code, msg string) wireErrorResp {
	var e wireErrorResp
	e.Error.Code = code
	e.Error.Message = msg
	return e
}

// callbackBody is the inbound callback envelope (HoneyCoin → SharkPay).
type callbackBody struct {
	Type        string `json:"type"`
	Ref         string `json:"ref"`
	Status      string `json:"status"`
	AmountMinor int64  `json:"amount_minor"`
	Currency    string `json:"currency"`
	Exponent    int    `json:"exponent"`
}

// ---------------------------------------------------------------------------
// Independent crypto (does NOT reuse the SUT's signing helpers).
// ---------------------------------------------------------------------------

// hmacHex returns hex HMAC-SHA256(key, msg).
func hmacHex(key, msg []byte) string {
	mac := hmac.New(sha256.New, key)
	mac.Write(msg)
	return hex.EncodeToString(mac.Sum(nil))
}

// signRequestMessage implements the HoneyCoin outbound request signature:
// HMAC-SHA256(key, ts "\n" method "\n" path "\n" body).
func signRequestMessage(key []byte, ts, method, path string, body []byte) string {
	var msg bytes.Buffer
	msg.WriteString(ts)
	msg.WriteString("\n")
	msg.WriteString(method)
	msg.WriteString("\n")
	msg.WriteString(path)
	msg.WriteString("\n")
	msg.Write(body)
	return hmacHex(key, msg.Bytes())
}

// verifyRequestSignature validates an outbound request signature against
// the local (independent) implementation of the scheme.
func verifyRequestSignature(key []byte, ts, method, path string, body []byte, hexSig string) bool {
	if ts == "" || hexSig == "" {
		return false
	}
	sig, err := hex.DecodeString(hexSig)
	if err != nil {
		return false
	}
	local, err := hex.DecodeString(signRequestMessage(key, ts, method, path, body))
	if err != nil {
		return false
	}
	return hmac.Equal(local, sig)
}

// signCallbackMessage implements the inbound callback signature:
// HMAC-SHA256(secret, ts "." raw body).
func signCallbackMessage(secret []byte, ts string, body []byte) string {
	var msg bytes.Buffer
	msg.WriteString(ts)
	msg.WriteString(".")
	msg.Write(body)
	return hmacHex(secret, msg.Bytes())
}

// ---------------------------------------------------------------------------
// Fake server.
// ---------------------------------------------------------------------------

// ServerConfig parameterizes the fake's wire behavior. The zero value is a
// healthy, correctly-signing HoneyCoin speaking the mapping contract.
type ServerConfig struct {
	// SigningKey verifies inbound request signatures (default
	// DefaultSigningKey). Configure the ADAPTER with a different key to
	// simulate a signing-key mismatch.
	SigningKey []byte
	// CallbackSecret signs emitted callbacks (default
	// DefaultCallbackSecret).
	CallbackSecret []byte
	// Latency delays every response — pair with a short adapter timeout
	// to inject deadline failures.
	Latency time.Duration
	// StatusCode != 0 makes every endpoint respond with this HTTP status
	// and an error envelope carrying ErrorCode (or "injected_failure").
	// 5xx values are what trip the adapter's breaker.
	StatusCode int
	// ErrorCode overrides the injected error envelope's code (used with
	// StatusCode to inject mapped business rejections like
	// "unsupported_operation").
	ErrorCode string
	// MalformedBody makes the fake return HTTP 200 with a non-JSON body
	// (protocol violation on a transport-healthy wire).
	MalformedBody bool
	// EmptyTransferID makes initiate respond 202 WITHOUT the transfer id
	// (malformed success the adapter must fail closed on).
	EmptyTransferID bool
	// RejectUnsigned responds 401 invalid_signature when the inbound
	// request signature does not verify (auth enforcement knob).
	RejectUnsigned bool
	// ReconLines are the canned reconciliation report lines. Lines with a
	// zero OccurredAt are always returned; others are filtered to the
	// requested [From, To) window. Use mixed statuses for partial-failure
	// scenarios (unmapped statuses must surface as UNKNOWN lines).
	ReconLines []ReconLine
}

// ReconLine is one canned reconciliation line.
type ReconLine struct {
	ID          string
	Status      string
	AmountMinor int64
	FeeMinor    int64
	Currency    string
	Exponent    int
	OccurredAt  time.Time
}

// Transfer is the fake's record of one provider-side transfer.
type Transfer struct {
	ID          string
	Status      string
	AmountMinor int64
	Currency    string
	Exponent    int
	Reverses    string // original transfer id when this is a reversal
}

// RecordedRequest is one observed inbound wire request (forensics).
type RecordedRequest struct {
	Method         string
	Path           string
	IdempotencyKey string
	Timestamp      string
	Signature      string
	SignatureValid bool
	Body           []byte
}

// WireResponse is one observed outbound wire response (DoSigned result).
type WireResponse struct {
	StatusCode int
	Body       []byte
	Header     http.Header
}

// FakeHoneyCoin is an in-process fake HoneyCoin server. It counts every
// wire effect (transfers and reversals actually created), records every
// request, verifies request signatures, and exposes knobs for failure
// injection. Concurrency-safe.
type FakeHoneyCoin struct {
	mu            sync.Mutex
	cfg           ServerConfig
	now           func() time.Time
	srv           *httptest.Server
	transfers     map[string]*Transfer
	byIdemKey     map[string]string // idempotency key → transfer id
	seq           int
	effects       int // money-moving upstream effects (initiate + reverse)
	requests      []RecordedRequest
	badSignatures int
}

// NewFakeHoneyCoin starts the fake server. Zero-valued ServerConfig fields
// are filled with the documented defaults.
func NewFakeHoneyCoin(cfg ServerConfig) *FakeHoneyCoin {
	if len(cfg.SigningKey) == 0 {
		cfg.SigningKey = []byte(DefaultSigningKey)
	}
	if len(cfg.CallbackSecret) == 0 {
		cfg.CallbackSecret = []byte(DefaultCallbackSecret)
	}
	f := &FakeHoneyCoin{
		cfg:       cfg,
		now:       time.Now,
		transfers: make(map[string]*Transfer),
		byIdemKey: make(map[string]string),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST "+PathQuotes, f.handleQuote)
	mux.HandleFunc("POST "+PathTransfers, f.handleTransfers)
	mux.HandleFunc("GET "+PathTransfers+"/{ref}", f.handleTransferStatus)
	mux.HandleFunc("POST "+PathTransfers+"/{ref}/cancel", f.handleCancel)
	mux.HandleFunc("POST "+PathTransfers+"/{ref}/reverse", f.handleReverse)
	mux.HandleFunc("POST "+PathReconciliation, f.handleRecon)
	f.srv = httptest.NewServer(mux)
	return f
}

// URL returns the fake server's base URL.
func (f *FakeHoneyCoin) URL() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.srv.URL
}

// Close shuts the fake server down.
func (f *FakeHoneyCoin) Close() {
	f.mu.Lock()
	srv := f.srv
	f.mu.Unlock()
	srv.Close()
}

// Now returns the fake's clock.
func (f *FakeHoneyCoin) Now() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.now()
}

// ---------------------------------------------------------------------------
// Failure-injection setters (mutable at runtime).
// ---------------------------------------------------------------------------

func (f *FakeHoneyCoin) SetLatency(d time.Duration) {
	f.mu.Lock()
	f.cfg.Latency = d
	f.mu.Unlock()
}

func (f *FakeHoneyCoin) SetStatusCode(code int) {
	f.mu.Lock()
	f.cfg.StatusCode = code
	f.mu.Unlock()
}

func (f *FakeHoneyCoin) SetErrorCode(code string) {
	f.mu.Lock()
	f.cfg.ErrorCode = code
	f.mu.Unlock()
}

func (f *FakeHoneyCoin) SetMalformedBody(on bool) {
	f.mu.Lock()
	f.cfg.MalformedBody = on
	f.mu.Unlock()
}

func (f *FakeHoneyCoin) SetEmptyTransferID(on bool) {
	f.mu.Lock()
	f.cfg.EmptyTransferID = on
	f.mu.Unlock()
}

func (f *FakeHoneyCoin) SetRejectUnsigned(on bool) {
	f.mu.Lock()
	f.cfg.RejectUnsigned = on
	f.mu.Unlock()
}

func (f *FakeHoneyCoin) SetSigningKey(key []byte) {
	f.mu.Lock()
	f.cfg.SigningKey = key
	f.mu.Unlock()
}

func (f *FakeHoneyCoin) SetReconLines(lines []ReconLine) {
	f.mu.Lock()
	f.cfg.ReconLines = lines
	f.mu.Unlock()
}

// SetTransferStatus mutates a stored transfer's wire status (drives Poll
// mapping scenarios and lifecycle transitions). Returns false if the
// transfer is unknown.
func (f *FakeHoneyCoin) SetTransferStatus(id, status string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	tr, ok := f.transfers[id]
	if !ok {
		return false
	}
	tr.Status = status
	return true
}

// ---------------------------------------------------------------------------
// Observability for assertions.
// ---------------------------------------------------------------------------

// TotalRequests returns how many wire requests reached the server (the
// "did the call actually go out" oracle for circuit-breaker scenarios).
func (f *FakeHoneyCoin) TotalRequests() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.requests)
}

// RequestsTo counts recorded requests whose path equals path exactly.
func (f *FakeHoneyCoin) RequestsTo(path string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, r := range f.requests {
		if r.Path == path {
			n++
		}
	}
	return n
}

// BadSignatures returns how many inbound requests failed signature
// verification.
func (f *FakeHoneyCoin) BadSignatures() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.badSignatures
}

// Effects returns the number of money-moving upstream effects actually
// created (initiates + reverses). The idempotency oracle: same
// X-Idempotency-Key ⇒ exactly one effect.
func (f *FakeHoneyCoin) Effects() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.effects
}

// RecordedRequests returns a copy of all observed wire requests.
func (f *FakeHoneyCoin) RecordedRequests() []RecordedRequest {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]RecordedRequest, len(f.requests))
	copy(out, f.requests)
	return out
}

// Transfer returns a copy of the stored transfer.
func (f *FakeHoneyCoin) Transfer(id string) (Transfer, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	tr, ok := f.transfers[id]
	if !ok {
		return Transfer{}, false
	}
	return *tr, true
}

// ---------------------------------------------------------------------------
// Request gate: record, authenticate, inject.
// ---------------------------------------------------------------------------

// gate records the request, verifies its signature, then applies the
// latency / status-code / malformed-body knobs. It returns (body, false)
// when the response has already been written.
func (f *FakeHoneyCoin) gate(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeWireError(w, http.StatusBadRequest, "bad_request", "unreadable body: "+err.Error())
		return nil, false
	}
	ts := r.Header.Get(HeaderRequestTimestamp)
	sig := r.Header.Get(HeaderRequestSignature)
	f.mu.Lock()
	cfg := f.cfg // shallow copy; slices are read-only
	valid := verifyRequestSignature(cfg.SigningKey, ts, r.Method, r.URL.Path, body, sig)
	f.requests = append(f.requests, RecordedRequest{
		Method:         r.Method,
		Path:           r.URL.Path,
		IdempotencyKey: r.Header.Get(HeaderIdempotencyKey),
		Timestamp:      ts,
		Signature:      sig,
		SignatureValid: valid,
		Body:           append([]byte(nil), body...),
	})
	if !valid {
		f.badSignatures++
	}
	f.mu.Unlock()

	// Auth enforcement knob: reject requests whose signature does not
	// verify (401 — a business rejection, must NOT trip the breaker).
	if !valid && cfg.RejectUnsigned {
		writeWireError(w, http.StatusUnauthorized, "invalid_signature",
			"conformance knob RejectUnsigned: request signature verification failed")
		return nil, false
	}
	// Latency knob: delay before responding (timeout injection).
	if cfg.Latency > 0 {
		time.Sleep(cfg.Latency)
	}
	// Injected status knob: every endpoint answers with cfg.StatusCode.
	if cfg.StatusCode != 0 {
		code := cfg.ErrorCode
		if code == "" {
			code = "injected_failure"
		}
		writeWireError(w, cfg.StatusCode, code,
			fmt.Sprintf("conformance knob: injected HTTP %d", cfg.StatusCode))
		return nil, false
	}
	// Malformed-body knob: HTTP 200 + non-JSON body.
	if cfg.MalformedBody {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("{{{ not json — conformance malformed-body knob"))
		return nil, false
	}
	return body, true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeWireError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, newWireError(code, msg))
}

func respondTransfer(w http.ResponseWriter, status int, tr Transfer, emptyID bool) {
	resp := transferResponse{
		ID:          tr.ID,
		Status:      tr.Status,
		Currency:    tr.Currency,
		AmountMinor: tr.AmountMinor,
		Reverses:    tr.Reverses,
	}
	if emptyID {
		resp.ID = ""
	}
	writeJSON(w, status, resp)
}

// ---------------------------------------------------------------------------
// Endpoint handlers.
// ---------------------------------------------------------------------------

func (f *FakeHoneyCoin) handleQuote(w http.ResponseWriter, r *http.Request) {
	body, ok := f.gate(w, r)
	if !ok {
		return
	}
	var req quoteRequest
	if err := json.Unmarshal(body, &req); err != nil {
		writeWireError(w, http.StatusBadRequest, "bad_request", "malformed quote request: "+err.Error())
		return
	}
	f.mu.Lock()
	f.seq++
	seq := f.seq
	now := f.now()
	f.mu.Unlock()
	// Integer fee arithmetic only — never floats (DATA-MODEL §1):
	// fee = amount/200 + flat; debit = receive + fee.
	fee := req.AmountMinor/200 + CannedFeeFlatMinor
	writeJSON(w, http.StatusOK, quoteResponse{
		QuoteID:      fmt.Sprintf("hcq_%06d", seq),
		DebitMinor:   req.AmountMinor + fee,
		FeeMinor:     fee,
		ReceiveMinor: req.AmountMinor,
		Currency:     req.Currency,
		Exponent:     req.Exponent,
		ExpiresAt:    now.Add(30 * time.Second),
	})
}

func (f *FakeHoneyCoin) handleTransfers(w http.ResponseWriter, r *http.Request) {
	body, ok := f.gate(w, r)
	if !ok {
		return
	}
	var req transferRequest
	if err := json.Unmarshal(body, &req); err != nil {
		writeWireError(w, http.StatusBadRequest, "bad_request", "malformed transfer request: "+err.Error())
		return
	}
	idem := r.Header.Get(HeaderIdempotencyKey)

	f.mu.Lock()
	// Provider-side idempotency: the same key returns the ORIGINAL
	// transfer (with its current status) and creates NO new effect.
	if idem != "" {
		if id, seen := f.byIdemKey[idem]; seen {
			tr := *f.transfers[id]
			empty := f.cfg.EmptyTransferID
			f.mu.Unlock()
			respondTransfer(w, http.StatusAccepted, tr, empty)
			return
		}
	}
	f.seq++
	id := CannedTransferID
	if f.seq > 1 {
		id = fmt.Sprintf("hct_stub_%06d", f.seq)
	}
	tr := Transfer{
		ID:          id,
		Status:      CannedInitStatus,
		AmountMinor: req.AmountMinor,
		Currency:    req.Currency,
		Exponent:    req.Exponent,
	}
	f.transfers[id] = &tr
	if idem != "" {
		f.byIdemKey[idem] = id
	}
	f.effects++
	empty := f.cfg.EmptyTransferID
	f.mu.Unlock()
	respondTransfer(w, http.StatusAccepted, tr, empty)
}

func (f *FakeHoneyCoin) handleTransferStatus(w http.ResponseWriter, r *http.Request) {
	_, ok := f.gate(w, r)
	if !ok {
		return
	}
	ref := r.PathValue("ref")
	f.mu.Lock()
	tr, found := f.transfers[ref]
	var out Transfer
	if found {
		out = *tr
	}
	f.mu.Unlock()
	if !found {
		writeWireError(w, http.StatusNotFound, "not_found", "transfer "+ref+" not found")
		return
	}
	respondTransfer(w, http.StatusOK, out, false)
}

func (f *FakeHoneyCoin) handleCancel(w http.ResponseWriter, r *http.Request) {
	_, ok := f.gate(w, r)
	if !ok {
		return
	}
	ref := r.PathValue("ref")
	f.mu.Lock()
	tr, found := f.transfers[ref]
	if !found {
		f.mu.Unlock()
		writeWireError(w, http.StatusNotFound, "not_found", "transfer "+ref+" not found")
		return
	}
	tr.Status = "CANCELLED"
	out := *tr
	f.mu.Unlock()
	respondTransfer(w, http.StatusOK, out, false)
}

func (f *FakeHoneyCoin) handleReverse(w http.ResponseWriter, r *http.Request) {
	_, ok := f.gate(w, r)
	if !ok {
		return
	}
	ref := r.PathValue("ref")
	idem := r.Header.Get(HeaderIdempotencyKey)

	f.mu.Lock()
	orig, found := f.transfers[ref]
	if !found {
		f.mu.Unlock()
		writeWireError(w, http.StatusNotFound, "not_found", "transfer "+ref+" not found")
		return
	}
	// Provider-side idempotency for the derived "reverse:<ref>" key.
	if idem != "" {
		if id, seen := f.byIdemKey[idem]; seen {
			rev := *f.transfers[id]
			f.mu.Unlock()
			respondTransfer(w, http.StatusOK, rev, false)
			return
		}
	}
	f.seq++
	rid := fmt.Sprintf("hct_stub_%06d", f.seq)
	rev := Transfer{
		ID:          rid,
		Status:      CannedInitStatus,
		AmountMinor: orig.AmountMinor,
		Currency:    orig.Currency,
		Exponent:    orig.Exponent,
		Reverses:    orig.ID,
	}
	f.transfers[rid] = &rev
	if idem != "" {
		f.byIdemKey[idem] = rid
	}
	f.effects++
	f.mu.Unlock()
	respondTransfer(w, http.StatusOK, rev, false)
}

func (f *FakeHoneyCoin) handleRecon(w http.ResponseWriter, r *http.Request) {
	body, ok := f.gate(w, r)
	if !ok {
		return
	}
	var req reconRequest
	if err := json.Unmarshal(body, &req); err != nil {
		writeWireError(w, http.StatusBadRequest, "bad_request", "malformed reconciliation request: "+err.Error())
		return
	}
	f.mu.Lock()
	lines := f.cfg.ReconLines
	f.mu.Unlock()
	out := make([]reconLine, 0, len(lines))
	for _, l := range lines {
		// Window [From, To): zero OccurredAt lines are always reported.
		if !l.OccurredAt.IsZero() && (l.OccurredAt.Before(req.From) || !l.OccurredAt.Before(req.To)) {
			continue
		}
		out = append(out, reconLine{
			ID:          l.ID,
			Status:      l.Status,
			AmountMinor: l.AmountMinor,
			FeeMinor:    l.FeeMinor,
			Currency:    l.Currency,
			Exponent:    l.Exponent,
			OccurredAt:  l.OccurredAt,
		})
	}
	writeJSON(w, http.StatusOK, reconResponse{Lines: out})
}

// ---------------------------------------------------------------------------
// Callback factory (forgery suite).
// ---------------------------------------------------------------------------

// CallbackOptions tweaks the emitted callback envelope — every field is a
// forgery vector (wrong secret, tampered/missing signature, stale or
// future timestamp, foreign provider, non-JSON body).
type CallbackOptions struct {
	Timestamp       time.Time // zero → now
	Secret          []byte    // nil → the fake's callback secret
	Signature       string    // non-empty → override (use non-hex for forgeries)
	TamperSignature bool      // flip the last hex nibble of the signature
	DropSignature   bool      // omit the signature header
	DropTimestamp   bool      // omit the timestamp header
	ProviderName    string    // "" → "honeycoin"; use a foreign name to test routing
	Body            []byte    // nil → build from the typed fields
}

// MakeCallback builds a provider.Callback exactly as the real HoneyCoin
// would: JSON envelope signed with HMAC-SHA256(secret, ts "." body).
func (f *FakeHoneyCoin) MakeCallback(ref, status string, amountMinor int64, currency string, exponent int, opts CallbackOptions) providers.Callback {
	body := opts.Body
	if body == nil {
		body, _ = json.Marshal(callbackBody{
			Type:        "transfer.status",
			Ref:         ref,
			Status:      status,
			AmountMinor: amountMinor,
			Currency:    currency,
			Exponent:    exponent,
		})
	}
	ts := opts.Timestamp
	if ts.IsZero() {
		ts = f.Now()
	}
	tsStr := strconv.FormatInt(ts.Unix(), 10)
	secret := opts.Secret
	if secret == nil {
		f.mu.Lock()
		secret = f.cfg.CallbackSecret
		f.mu.Unlock()
	}
	sig := opts.Signature
	if sig == "" {
		sig = signCallbackMessage(secret, tsStr, body)
	}
	if opts.TamperSignature {
		b := []byte(sig)
		if len(b) > 0 {
			if b[len(b)-1] == '0' {
				b[len(b)-1] = '1'
			} else {
				b[len(b)-1] = '0'
			}
		}
		sig = string(b)
	}
	headers := map[string]string{}
	if !opts.DropTimestamp {
		headers[HeaderCallbackTimestamp] = tsStr
	}
	if !opts.DropSignature {
		headers[HeaderCallbackSignature] = sig
	}
	provider := opts.ProviderName
	if provider == "" {
		provider = "honeycoin"
	}
	return providers.Callback{Provider: provider, Headers: headers, Body: body}
}

// ---------------------------------------------------------------------------
// Signed direct wire access (WireMock-mapping drift tests, knob self-tests).
// ---------------------------------------------------------------------------

// DoSigned performs an HTTP request against the fake with a correctly
// signed HoneyCoin request envelope, mirroring the adapter's outbound
// signing (ts + hex HMAC over ts "\n" method "\n" path "\n" body) but
// computed with the local independent implementation.
func (f *FakeHoneyCoin) DoSigned(method, path string, body []byte, idemKey string) (WireResponse, error) {
	ts := strconv.FormatInt(f.Now().Unix(), 10)
	f.mu.Lock()
	key := f.cfg.SigningKey
	base := f.srv.URL
	f.mu.Unlock()
	req, err := http.NewRequest(method, base+path, bytes.NewReader(body))
	if err != nil {
		return WireResponse{}, fmt.Errorf("fake: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(HeaderRequestTimestamp, ts)
	req.Header.Set(HeaderRequestSignature, signRequestMessage(key, ts, method, path, body))
	if idemKey != "" {
		req.Header.Set(HeaderIdempotencyKey, idemKey)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return WireResponse{}, fmt.Errorf("fake: do request: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return WireResponse{}, fmt.Errorf("fake: read response: %w", err)
	}
	return WireResponse{StatusCode: resp.StatusCode, Body: raw, Header: resp.Header}, nil
}
