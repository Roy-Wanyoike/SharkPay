// Package health implements the per-provider circuit breaker
// (ARCHITECTURE.md §4.3, SECURITY.md §4 "money-movement safety"):
//
//	5 failures within 30s  →  breaker OPEN
//	OPEN                    →  calls rejected; router fails over
//	after 60s in OPEN       →  HALF-OPEN, a single probe call is allowed
//	probe succeeds          →  CLOSED (healthy again)
//	probe fails             →  OPEN for another 60s
//
// The breaker is deliberately dumb: it knows nothing about providers or
// money. Callers decide what counts as a failure (the HoneyCoin adapter
// trips on transport errors, timeouts and HTTP 5xx — NOT on 4xx business
// rejections, which prove the provider is alive).
//
// SECURITY §4: the breaker never triggers retries. An ambiguous debit is
// parked, never re-issued; fail-over is the router's decision.
package health

import (
	"errors"
	"fmt"
	"sync"
	"time"
)

// ErrBreakerOpen is returned by Allow while the breaker is OPEN (and while
// a half-open probe is already in flight). Adapters translate it to
// provider.ErrProviderUnavailable so the router can fail over.
var ErrBreakerOpen = errors.New("sharkpay/health: circuit breaker open")

// State is the breaker state.
type State int

const (
	// StateClosed: calls flow; failures are counted in the rolling window.
	StateClosed State = iota
	// StateOpen: calls rejected until OpenTimeout elapses.
	StateOpen
	// StateHalfOpen: one (ProbeLimit) probe call is admitted to test
	// recovery.
	StateHalfOpen
)

func (s State) String() string {
	switch s {
	case StateClosed:
		return "CLOSED"
	case StateOpen:
		return "OPEN"
	case StateHalfOpen:
		return "HALF_OPEN"
	default:
		return "INVALID"
	}
}

// Config parameterizes a breaker. The zero value means "defaults below",
// which are the normative platform values — change only with a decision-log
// entry (ARCHITECTURE §4.3 numbers).
type Config struct {
	// FailureThreshold: failures within FailureWindow that trip the
	// breaker. Default 5.
	FailureThreshold int
	// FailureWindow: rolling window for counting failures. Default 30s.
	FailureWindow time.Duration
	// OpenTimeout: how long the breaker stays OPEN before a half-open
	// probe. Default 60s.
	OpenTimeout time.Duration
	// ProbeLimit: concurrent calls admitted while HALF-OPEN. Default 1.
	ProbeLimit int
	// Now: clock injection for tests. Default time.Now.
	Now func() time.Time
}

func (c Config) withDefaults() Config {
	if c.FailureThreshold <= 0 {
		c.FailureThreshold = 5
	}
	if c.FailureWindow <= 0 {
		c.FailureWindow = 30 * time.Second
	}
	if c.OpenTimeout <= 0 {
		c.OpenTimeout = 60 * time.Second
	}
	if c.ProbeLimit <= 0 {
		c.ProbeLimit = 1
	}
	if c.Now == nil {
		c.Now = time.Now
	}
	return c
}

// DefaultConfig returns the normative breaker parameters:
// 5 failures / 30s → OPEN 60s → half-open probe.
func DefaultConfig() Config {
	return Config{}.withDefaults()
}

// Breaker is one named circuit breaker. Concurrency-safe.
type Breaker struct {
	mu       sync.Mutex
	name     string
	cfg      Config
	state    State
	failures []time.Time // failure timestamps inside the window
	openedAt time.Time
	probes   int // in-flight half-open probes
}

// NewBreaker builds a breaker for name with cfg (zero values → defaults).
func NewBreaker(name string, cfg Config) *Breaker {
	return &Breaker{name: name, cfg: cfg.withDefaults()}
}

// Name returns the breaker's provider name.
func (b *Breaker) Name() string { return b.name }

// State returns the current breaker state.
func (b *Breaker) State() State {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.state
}

// FailureCount returns how many failures are currently inside the window
// (diagnostics/metrics).
func (b *Breaker) FailureCount() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.failures)
}

// Allow reports whether a call may proceed. While OPEN (past
// OpenTimeout) it transitions to HALF-OPEN and admits one probe.
func (b *Breaker) Allow() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	switch b.state {
	case StateClosed:
		return nil
	case StateOpen:
		if b.cfg.Now().Sub(b.openedAt) >= b.cfg.OpenTimeout {
			b.state = StateHalfOpen
			b.probes = 1
			return nil // admit the recovery probe
		}
		return fmt.Errorf("%w (provider %s, open since %s)", ErrBreakerOpen, b.name, b.openedAt.UTC().Format(time.RFC3339))
	default: // StateHalfOpen
		if b.probes < b.cfg.ProbeLimit {
			b.probes++
			return nil
		}
		return fmt.Errorf("%w (provider %s, half-open probe in flight)", ErrBreakerOpen, b.name)
	}
}

// RecordSuccess closes the breaker and forgets prior failures.
func (b *Breaker) RecordSuccess() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.state = StateClosed
	b.failures = nil
	b.probes = 0
}

// RecordFailure records a failure. CLOSED: appends to the window and trips
// OPEN at the threshold. HALF-OPEN: re-opens immediately. OPEN: no-op.
func (b *Breaker) RecordFailure() {
	b.mu.Lock()
	defer b.mu.Unlock()
	now := b.cfg.Now()
	switch b.state {
	case StateClosed:
		b.failures = append(b.failures, now)
		// prune failures that fell out of the window
		keep := b.failures[:0]
		for _, ts := range b.failures {
			if now.Sub(ts) < b.cfg.FailureWindow {
				keep = append(keep, ts)
			}
		}
		b.failures = keep
		if len(b.failures) >= b.cfg.FailureThreshold {
			b.trip(now)
		}
	case StateHalfOpen:
		// the recovery probe failed — open again for a full timeout
		b.trip(now)
	case StateOpen:
		// already open; nothing to do
	}
}

func (b *Breaker) trip(now time.Time) {
	b.state = StateOpen
	b.openedAt = now
	b.failures = nil
	b.probes = 0
}

// Reset forces the breaker CLOSED and forgets failures. Test and ops
// tooling only (e.g. between conformance sections, or an operator
// re-arming a breaker after manual provider recovery).
func (b *Breaker) Reset() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.state = StateClosed
	b.failures = nil
	b.probes = 0
	b.openedAt = time.Time{}
}

// Manager owns one breaker per provider name (SECURITY §4: "circuit
// breakers per provider"), so independent rails fail independently.
type Manager struct {
	mu       sync.Mutex
	cfg      Config
	breakers map[string]*Breaker
}

// NewManager creates a breaker manager; cfg defaults apply per breaker.
func NewManager(cfg Config) *Manager {
	return &Manager{cfg: cfg, breakers: make(map[string]*Breaker)}
}

// Breaker returns the breaker for name, creating it on first use.
func (m *Manager) Breaker(name string) *Breaker {
	m.mu.Lock()
	defer m.mu.Unlock()
	if b, ok := m.breakers[name]; ok {
		return b
	}
	b := NewBreaker(name, m.cfg)
	m.breakers[name] = b
	return b
}
