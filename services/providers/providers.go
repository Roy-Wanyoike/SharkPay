// Package providers is the public surface of the SharkPay provider
// gateway. The implementation lives under internal/ (single source of
// truth); this file re-exports it with type aliases so that:
//
//   - the conformance suite (tests/providers) and future adapters can
//     program against the interface without importing internal packages
//     (Go forbids cross-module internal imports),
//   - the payments/payouts orchestration agents integrate against ONE
//     import path: github.com/Roy-Wanyoike/SharkPay/services/providers.
//
// Aliases are exact type identities, not copies: a value implementing
// internal/provider.Provider satisfies providers.Provider and vice versa.
package providers

import (
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/callback"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/health"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/honeycoin"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/router"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/store"
)

// ---------------------------------------------------------------------------
// Provider domain (internal/provider — normative interface, ARCHITECTURE §4.2)
// ---------------------------------------------------------------------------

// Provider is the uniform rail abstraction every adapter implements.
type Provider = provider.Provider

// Money: int64 minor units + currency + exponent. Never floats.
type Money = provider.Money

// TransferStatus: PENDING | PROCESSING | SUCCEEDED | FAILED | RETURNED |
// UNKNOWN. UNKNOWN is ambiguous — park + alert, never auto-retry
// (SECURITY §4; see internal/provider doc comment).
type TransferStatus = provider.TransferStatus

// Capabilities: currencies, rails, reversals, on-chain.
type Capabilities = provider.Capabilities

// Destination: external payout endpoint (msisdn / bank / on_chain / wallet).
type Destination = provider.Destination

// QuoteRequest prices a prospective transfer.
type QuoteRequest = provider.QuoteRequest

// Quote: Debit = Receive + Fee, valid until ExpiresAt.
type Quote = provider.Quote

// InitiateRequest starts a transfer; TransactionKey is the adapter-level
// idempotency key.
type InitiateRequest = provider.InitiateRequest

// ProviderRef identifies a transfer at a provider.
type ProviderRef = provider.ProviderRef

// Callback: inbound provider notification (raw body + verification headers).
type Callback = provider.Callback

// Window: reconciliation window.
type Window = provider.Window

// ProviderLine: one reconciliation report line.
type ProviderLine = provider.ProviderLine

// AdapterCall: one audited provider call (redacted request/response).
type AdapterCall = provider.AdapterCall

// Registry: registered providers + health states.
type Registry = provider.Registry

// HealthState of a registered provider.
type HealthState = provider.HealthState

// Transfer status values.
const (
	StatusPending    = provider.StatusPending
	StatusProcessing = provider.StatusProcessing
	StatusSucceeded  = provider.StatusSucceeded
	StatusFailed     = provider.StatusFailed
	StatusReturned   = provider.StatusReturned
	StatusUnknown    = provider.StatusUnknown
)

// Registry health values.
const (
	HealthHealthy  = provider.HealthHealthy
	HealthDegraded = provider.HealthDegraded
	HealthOpen     = provider.HealthOpen
	HealthUnknown  = provider.HealthUnknown
)

// Audit outcome values.
const (
	OutcomeSuccess     = provider.OutcomeSuccess
	OutcomeFailure     = provider.OutcomeFailure
	OutcomeUnavailable = provider.OutcomeUnavailable
)

// Typed provider errors (match with errors.Is).
var (
	// ErrProviderUnavailable: fail-over signal (breaker open / unreachable).
	ErrProviderUnavailable = provider.ErrProviderUnavailable
	// ErrUnsupported: operation or capability not supported.
	ErrUnsupported = provider.ErrUnsupported
	// ErrUnsupportedCurrency: currency outside capability set.
	ErrUnsupportedCurrency = provider.ErrUnsupportedCurrency
	// ErrNotFound: provider reference unknown.
	ErrNotFound = provider.ErrNotFound

	// NewRegistry builds the provider registry.
	NewRegistry = provider.NewRegistry
)

// ---------------------------------------------------------------------------
// Circuit breaker (internal/health)
// ---------------------------------------------------------------------------

// Breaker: 5 failures/30s → OPEN 60s → half-open probe.
type Breaker = health.Breaker

// BreakerConfig parameterizes a breaker (zero value = normative defaults).
type BreakerConfig = health.Config

// BreakerManager owns one breaker per provider name.
type BreakerManager = health.Manager

// Breaker states.
const (
	BreakerClosed   = health.StateClosed
	BreakerOpen     = health.StateOpen
	BreakerHalfOpen = health.StateHalfOpen
)

var (
	// ErrBreakerOpen is returned by Breaker.Allow while open.
	ErrBreakerOpen = health.ErrBreakerOpen
	// NewBreaker builds a named breaker.
	NewBreaker = health.NewBreaker
	// NewBreakerManager builds a per-provider breaker manager.
	NewBreakerManager = health.NewManager
	// DefaultBreakerConfig returns the normative breaker parameters.
	DefaultBreakerConfig = health.DefaultConfig
)

// ---------------------------------------------------------------------------
// Callback verification (internal/callback)
// ---------------------------------------------------------------------------

// Verifier authenticates inbound provider callbacks.
type Verifier = callback.Verifier

// VerifierConfig tunes the verifier (±window, replay TTL, clock).
type VerifierConfig = callback.VerifierConfig

// ReplayCache: nonce store behind replay protection (Redis impl follow-up).
type ReplayCache = callback.ReplayCache

// MemoryReplayCache: in-memory TTL implementation.
type MemoryReplayCache = callback.MemoryReplayCache

// VerifyResult of Verifier.Verify: Verified or Rejected.
type VerifyResult = callback.Result

// Inbound callback header names.
const (
	CallbackTimestampHeader = callback.HeaderTimestamp
	CallbackSignatureHeader = callback.HeaderSignature
)

// Verification results.
const (
	Verified = callback.Verified
	Rejected = callback.Rejected
)

// Callback verification errors.
var (
	// ErrStale: timestamp outside the ±5 min window.
	ErrStale = callback.ErrStale
	// ErrBadSignature: HMAC mismatch.
	ErrBadSignature = callback.ErrBadSignature
	// ErrReplay: nonce seen within the TTL.
	ErrReplay = callback.ErrReplay
	// ErrMalformed: missing headers / timestamp / ref.
	ErrMalformed = callback.ErrMalformed

	// NewVerifier builds a callback verifier.
	NewVerifier = callback.NewVerifier
	// NewMemoryReplayCache builds an in-memory replay cache (wall clock).
	NewMemoryReplayCache = callback.NewMemoryReplayCache
	// NewMemoryReplayCacheWithClock builds one with an injectable clock.
	NewMemoryReplayCacheWithClock = callback.NewMemoryReplayCacheWithClock
)

// ---------------------------------------------------------------------------
// Audit store (internal/store)
// ---------------------------------------------------------------------------

// AuditStore persists the adapter_calls trail (PG impl in production).
type AuditStore = store.AuditStore

// MemoryAuditStore: in-memory implementation (tests/sandbox).
type MemoryAuditStore = store.MemoryStore

// NewMemoryAuditStore builds an in-memory audit store.
var NewMemoryAuditStore = store.NewMemoryStore

// ---------------------------------------------------------------------------
// HoneyCoin adapter (internal/honeycoin — launch reference implementation)
// ---------------------------------------------------------------------------

// HoneyCoinAdapter implements Provider over the HoneyCoin REST wire.
type HoneyCoinAdapter = honeycoin.Adapter

// HoneyCoinConfig configures the adapter (empty fields → env + defaults).
type HoneyCoinConfig = honeycoin.Config

// IdempotencyHeader carries our transaction key on state-changing calls.
const IdempotencyHeader = honeycoin.IdempotencyHeader

var (
	// NewHoneyCoin builds the HoneyCoin adapter.
	NewHoneyCoin = honeycoin.New
	// MapHoneyCoinStatus maps a HoneyCoin wire status to TransferStatus
	// (unmapped → UNKNOWN: park + alert, never auto-retry).
	MapHoneyCoinStatus = honeycoin.MapStatus
	// SignHoneyCoinRequest computes the outbound request signature
	// (HMAC-SHA256 over ts "\n" method "\n" path "\n" body).
	SignHoneyCoinRequest = honeycoin.SignRequest
	// VerifyHoneyCoinRequestMessage validates an outbound request signature.
	VerifyHoneyCoinRequestMessage = honeycoin.VerifyRequestMessage
	// ResolveHoneyCoinSigningKey resolves the signing key from an explicit
	// value or env ("vault:" refs are rejected until the Secrets Manager
	// integration lands).
	ResolveHoneyCoinSigningKey = honeycoin.ResolveSigningKey
)

// ---------------------------------------------------------------------------
// Router (internal/router)
// ---------------------------------------------------------------------------

// Router selects a provider candidate (hard filters, then weighted score).
type Router = router.Router

// RouterConfig: scoring weights (defaults 0.5 / 0.3 / 0.2).
type RouterConfig = router.Config

// RouteCandidate: capability + health + cost + p99 latency + MinTier.
type RouteCandidate = router.Candidate

// RouteRequest: amount, currency, rail, principal tier.
type RouteRequest = router.Request

var (
	// ErrNoCandidate: nothing survived the hard filters — fail closed.
	ErrNoCandidate = router.ErrNoCandidate
	// NewRouter builds a router (zero config = defaults).
	NewRouter = router.New
	// DefaultRouterConfig returns the documented default weights.
	DefaultRouterConfig = router.DefaultConfig
)
