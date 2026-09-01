// Package provider defines the uniform Provider abstraction that isolates
// every external payment rail (HoneyCoin at launch; M-Pesa, bank rails and
// EVM chains later) behind one interface.
//
// The Provider interface in this file is normative: it matches
// ARCHITECTURE.md §4.2 exactly. No domain service may import a provider
// SDK (ARCHITECTURE §1 rule 3); they talk to this interface only. A
// provider implementation is production-eligible only after passing the
// conformance suite in tests/providers (PRD FR-702, ARCHITECTURE §4.3).
//
// Money here is int64 minor units + currency + exponent — never floats.
package provider

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// Money is an exact monetary amount: integer minor units, an ISO-4217
// alpha-3 currency and the decimal exponent (DATA-MODEL §1: "no floats,
// no NUMERIC, ever"). E.g. {150000, "KES", 2} = 1,500.00 KES.
//
// This mirrors packages/go/money; the provider module keeps a local
// definition so it stays dependency-light (stdlib + uuid only) until the
// shared package lands, at which point it can be aliased.
type Money struct {
	AmountMinor int64  `json:"amount_minor"`
	Currency    string `json:"currency"`
	Exponent    int    `json:"exponent"`
}

// Validate checks the structural invariants of a Money value.
func (m Money) Validate() error {
	if len(m.Currency) != 3 {
		return fmt.Errorf("money: currency must be a 3-letter code, got %q", m.Currency)
	}
	for i := 0; i < len(m.Currency); i++ {
		c := m.Currency[i]
		if c < 'A' || c > 'Z' {
			return fmt.Errorf("money: currency must be uppercase A-Z, got %q", m.Currency)
		}
	}
	if m.Exponent < 0 || m.Exponent > 12 {
		return fmt.Errorf("money: exponent out of range [0,12], got %d", m.Exponent)
	}
	if m.AmountMinor < 0 {
		return fmt.Errorf("money: negative minor units (%d) not allowed", m.AmountMinor)
	}
	return nil
}

// TransferStatus is the rail-agnostic status of a provider transfer.
type TransferStatus string

const (
	// StatusPending: rail accepted the request; awaiting settlement.
	StatusPending TransferStatus = "PENDING"
	// StatusProcessing: settlement is in flight at the rail.
	StatusProcessing TransferStatus = "PROCESSING"
	// StatusSucceeded: settled at destination (rail-confirmed).
	StatusSucceeded TransferStatus = "SUCCEEDED"
	// StatusFailed: terminal failure; funds did not move.
	StatusFailed TransferStatus = "FAILED"
	// StatusReturned: funds came back (rail return or provider reversal).
	StatusReturned TransferStatus = "RETURNED"
	// StatusUnknown: the provider's answer could not be mapped to any
	// known state. See the AMBIGUITY CONTRACT below.
	StatusUnknown TransferStatus = "UNKNOWN"
)

// AMBIGUITY CONTRACT for StatusUnknown (SECURITY.md §4, PRD §5 principle 6):
//
// UNKNOWN means the provider response was ambiguous — money MAY OR MAY NOT
// have moved (unmapped status code, contradictory callback, malformed
// response). Callers MUST:
//
//  1. Park the payment/payout in its fail-safe state (PROCESSING) and raise
//     an ops alert — never apply a terminal transition.
//  2. NEVER auto-retry the underlying debit. Retrying an ambiguous debit can
//     double-spend; only explicit provider/human confirmation may resume,
//     fail or reverse the flow ("never auto-retry ambiguous debits").
//  3. Resolve through Poll, ReconcileReport or manual provider confirmation
//     before any ledger capture or compensation entry.
//
// Adapters return (StatusUnknown, nil) — ambiguity is a *state*, not an
// error; the park+alert decision belongs to the orchestrating workflow
// (Temporal), not the adapter.

// Capabilities advertises what a provider supports. The router hard-filters
// candidates on these (currency, rail, on-chain fit); Reversals gates
// Provider.Reverse (providers without the capability must return
// ErrUnsupported without touching the wire).
type Capabilities struct {
	// Currencies lists supported ISO-4217 codes (e.g. KES, USD, USDC).
	Currencies []string
	// Rails lists supported rails (e.g. "honeycoin", "mpesa", "bank", "on_chain").
	Rails []string
	// Reversals reports whether the provider supports Reverse.
	Reversals bool
	// OnChain reports whether the provider settles on-chain (EVM).
	OnChain bool
}

// SupportsCurrency reports whether cur is in the capability set.
func (c Capabilities) SupportsCurrency(cur string) bool {
	for _, x := range c.Currencies {
		if x == cur {
			return true
		}
	}
	return false
}

// SupportsRail reports whether rail is in the capability set.
func (c Capabilities) SupportsRail(rail string) bool {
	for _, x := range c.Rails {
		if x == rail {
			return true
		}
	}
	return false
}

// Destination is the external endpoint of a payout/transfer:
// {"type":"msisdn","details":{"msisdn":"+2547..."}}, {"type":"bank",...},
// {"type":"on_chain","details":{"network":"base","address":"0x..."}}.
type Destination struct {
	Type    string            `json:"type"`
	Details map[string]string `json:"details,omitempty"`
}

// QuoteRequest asks a provider for the economics of moving Amount over Rail.
type QuoteRequest struct {
	Amount      Money
	Rail        string
	Destination Destination
}

// Quote is a provider's price for a movement: the wallet is debited Debit,
// the destination receives Receive, the provider keeps Fee
// (Debit = Receive + Fee for same-currency V1 flows). ExpiresAt bounds the
// quote's validity (fx quote locking reuses this).
type Quote struct {
	ID        string
	Debit     Money
	Receive   Money
	Fee       Money
	ExpiresAt time.Time
}

// InitiateRequest starts a transfer at a provider. TransactionKey is OUR
// idempotency key (SECURITY §4: keys enforced at API, service, ledger and
// adapter layers, all derived from the original request key chain); the
// adapter must send it as the provider idempotency header so retries are
// safe end-to-end.
type InitiateRequest struct {
	TransactionKey string
	Amount         Money
	Rail           string
	Destination    Destination
	QuoteID        string            // optional locked quote
	Metadata       map[string]string // passthrough, no secrets
}

// ProviderRef identifies one transfer at a specific provider.
type ProviderRef struct {
	Provider string // provider name, e.g. "honeycoin"
	Ref      string // provider-side transfer id
}

// Callback is an inbound provider notification. Body must be the exact raw
// bytes received — the signature covers them — and Headers carry the
// verification material (X-SharkPay-Timestamp / X-SharkPay-Signature; see
// internal/callback for the scheme).
type Callback struct {
	Provider string
	Headers  map[string]string
	Body     []byte
}

// Window is a reconciliation window [From, To).
type Window struct {
	From time.Time
	To   time.Time
}

// Validate checks that the window is usable (both ends set, From before To).
func (w Window) Validate() error {
	if w.From.IsZero() || w.To.IsZero() {
		return errors.New("window: From and To must both be set")
	}
	if !w.From.Before(w.To) {
		return fmt.Errorf("window: From (%s) must be before To (%s)", w.From, w.To)
	}
	return nil
}

// ProviderLine is one line of a provider reconciliation report; the
// reconciliation service compares these against ledger postings
// (PRD D10, FR-1001).
type ProviderLine struct {
	Ref        string
	Status     TransferStatus
	Amount     Money
	Fee        Money
	OccurredAt time.Time
}

// Provider is the uniform rail abstraction (ARCHITECTURE.md §4.2 — normative).
// The launch reference implementation is internal/honeycoin; every future
// adapter copies its structure and must pass the conformance suite
// (tests/providers) before production use.
type Provider interface {
	// Name is the stable provider identifier ("honeycoin").
	Name() string

	// Capabilities advertises currencies, rails, reversals and on-chain
	// support. Callers must treat the result as immutable.
	Capabilities() Capabilities

	// Quote prices a prospective transfer.
	Quote(ctx context.Context, r QuoteRequest) (Quote, error)

	// Initiate starts a transfer. r.TransactionKey is the adapter-level
	// idempotency key. Implementations must log the call to the audit store
	// before returning.
	Initiate(ctx context.Context, r InitiateRequest) (ProviderRef, error)

	// Poll reads the current status of a transfer from the provider.
	// Unmapped provider states must return StatusUnknown (see the
	// AMBIGUITY CONTRACT above) — never a guessed state, never an error.
	Poll(ctx context.Context, ref ProviderRef) (TransferStatus, error)

	// HandleCallback verifies and applies an inbound provider callback,
	// returning the resulting status. Verification failures must surface
	// the callback package's sentinel errors (ErrBadSignature, ErrStale,
	// ErrReplay) so ingress can log/alert (SECURITY §6 detects webhook
	// signature failures).
	HandleCallback(ctx context.Context, cb Callback) (TransferStatus, error)

	// Cancel cancels a transfer that has not settled.
	Cancel(ctx context.Context, ref ProviderRef) error

	// Reverse pulls funds back; returns the reversal transfer's ref.
	// Providers without reversal capability return ErrUnsupported.
	Reverse(ctx context.Context, ref ProviderRef) (ProviderRef, error)

	// ReconcileReport returns the provider's settled/activity lines for a
	// window, for ledger↔provider agreement checking.
	ReconcileReport(ctx context.Context, window Window) ([]ProviderLine, error)
}

// Audit outcome values for AdapterCall.Outcome.
const (
	// OutcomeSuccess: the wire call completed with a 2xx and a usable body.
	OutcomeSuccess = "success"
	// OutcomeFailure: wire/transport error, HTTP 5xx, timeout, or a
	// rejected (4xx) business response.
	OutcomeFailure = "failure"
	// OutcomeUnavailable: the call was rejected by an open circuit breaker
	// before any wire traffic — the router may fail over immediately.
	OutcomeUnavailable = "unavailable"
)

// AdapterCall is one row of the adapter_calls audit trail (DATA-MODEL
// §3.5: "full request/response audit, redacted"). SECURITY §1 lists it as
// the repudiation control: every provider call must be audited BEFORE the
// adapter returns, including rejections from the circuit breaker.
//
// Request/Response bodies are redacted by the adapter before they reach
// the store: signature/secret-looking fields are replaced and auth headers
// are never recorded.
type AdapterCall struct {
	ID         string        `json:"id"`          // audit row id (uuid)
	Provider   string        `json:"provider"`    // e.g. "honeycoin"
	Method     string        `json:"method"`      // Provider method: Quote, Initiate, Poll, ...
	HTTPMethod string        `json:"http_method"` // GET/POST; "" for non-wire calls
	Path       string        `json:"path"`
	Ref        string        `json:"ref,omitempty"` // provider ref when known
	StatusCode int           `json:"status_code,omitempty"`
	Request    string        `json:"request,omitempty"`  // redacted JSON
	Response   string        `json:"response,omitempty"` // redacted JSON
	Latency    time.Duration `json:"latency"`
	Outcome    string        `json:"outcome"` // OutcomeSuccess | OutcomeFailure | OutcomeUnavailable
	StartedAt  time.Time     `json:"started_at"`
}

// Typed errors. Adapters wrap these with %w so callers can errors.Is them.
var (
	// ErrProviderUnavailable: the provider cannot serve the call right now
	// (circuit breaker open, unreachable). The router treats this as a
	// fail-over signal.
	ErrProviderUnavailable = errors.New("sharkpay/providers: provider unavailable")

	// ErrUnsupported: the operation or capability is not supported by the
	// provider (e.g. Reverse without reversal capability, wire error code
	// "unsupported_operation").
	ErrUnsupported = errors.New("sharkpay/providers: operation unsupported")

	// ErrUnsupportedCurrency: the currency is outside the provider's
	// capability set.
	ErrUnsupportedCurrency = errors.New("sharkpay/providers: currency unsupported")

	// ErrNotFound: the provider reference is unknown to the provider.
	ErrNotFound = errors.New("sharkpay/providers: provider reference not found")
)
