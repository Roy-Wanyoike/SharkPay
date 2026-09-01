package conformance

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers"
)

// scenario is one named, documented conformance row. doc states WHAT wire
// behavior is exercised and WHAT is expected; it is logged with every run
// and quoted verbatim in failure messages via the helpers below.
type scenario struct {
	name string
	doc  string
	run  func(t *testing.T)
}

// runScenarios executes a scenario table. Each row is an isolated subtest
// with its own fake server, adapter, breaker, verifier, replay cache and
// audit store.
func runScenarios(t *testing.T, scenarios []scenario) {
	t.Helper()
	for _, s := range scenarios {
		s := s
		t.Run(s.name, func(t *testing.T) {
			t.Helper()
			t.Logf("SCENARIO %s — %s", s.name, s.doc)
			s.run(t)
		})
	}
}

// EnvOpts tunes the scenario environment (adapter + fake + breaker wiring).
type EnvOpts struct {
	// AdapterTimeout is the per-request wire timeout (default 2s).
	AdapterTimeout time.Duration
	// AdapterSigningKey is the key the ADAPTER signs with (default
	// DefaultSigningKey — override to simulate a signing-key mismatch
	// against the fake's key).
	AdapterSigningKey []byte
	// Breaker configures the injected circuit breaker (default: the
	// normative 5 failures/30s → OPEN 60s).
	Breaker providers.BreakerConfig
	// Server carries the fake's failure-injection knobs.
	Server ServerConfig
}

// Env is a fully wired scenario environment: fake wire, real adapter,
// fresh breaker / verifier / replay cache / audit store. Nothing is shared
// between scenarios.
type Env struct {
	Fake    *FakeHoneyCoin
	Adapter providers.Provider
	Audit   *providers.MemoryAuditStore
	Breaker *providers.Breaker
}

// newEnv wires a scenario environment and registers cleanup.
func newEnv(t *testing.T, mods ...func(*EnvOpts)) *Env {
	t.Helper()
	opts := EnvOpts{
		AdapterTimeout:    2 * time.Second,
		AdapterSigningKey: []byte(DefaultSigningKey),
		Breaker:           providers.DefaultBreakerConfig(),
		Server: ServerConfig{
			SigningKey:     []byte(DefaultSigningKey),
			CallbackSecret: []byte(DefaultCallbackSecret),
		},
	}
	for _, mod := range mods {
		mod(&opts)
	}
	fake := NewFakeHoneyCoin(opts.Server)
	t.Cleanup(fake.Close)

	breaker := providers.NewBreaker("honeycoin", opts.Breaker)
	audit := providers.NewMemoryAuditStore()
	verifier := providers.NewVerifier([]byte(DefaultCallbackSecret), providers.NewMemoryReplayCache(), providers.VerifierConfig{})
	adapter, err := providers.NewHoneyCoin(providers.HoneyCoinConfig{
		BaseURL:        fake.URL(),
		SigningKey:     opts.AdapterSigningKey,
		CallbackSecret: []byte(DefaultCallbackSecret),
		Timeout:        opts.AdapterTimeout,
		Breaker:        breaker,
		Audit:          audit,
		Verifier:       verifier,
	})
	if err != nil {
		t.Fatalf("wiring scenario environment: providers.NewHoneyCoin: %v", err)
	}
	return &Env{Fake: fake, Adapter: adapter, Audit: audit, Breaker: breaker}
}

// mustInitiate drives the happy initiate path and fails the scenario with a
// full explanation on any error.
func mustInitiate(t *testing.T, env *Env, txKey string) providers.ProviderRef {
	t.Helper()
	ref, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
		TransactionKey: txKey,
		Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
		Rail:           "honeycoin",
		Destination:    providers.Destination{Type: "msisdn", Details: map[string]string{"msisdn": "+254700000001"}},
	})
	if err != nil {
		t.Fatalf("scenario %s: Initiate(%q) failed (wire was healthy): %v", t.Name(), txKey, err)
	}
	return ref
}

// auditRows returns the audit rows for one Provider method, in order.
func auditRows(t *testing.T, env *Env, method string) []providers.AdapterCall {
	t.Helper()
	var out []providers.AdapterCall
	for _, r := range env.Audit.Calls() {
		if r.Method == method {
			out = append(out, r)
		}
	}
	return out
}

// requireRows asserts an exact audit row count for a method and returns them.
func requireRows(t *testing.T, env *Env, method string, want int) []providers.AdapterCall {
	t.Helper()
	rows := auditRows(t, env, method)
	if len(rows) != want {
		t.Fatalf("scenario %s: audit trail must contain exactly %d %q row(s) (forensics: every exchange is audited), got %d — full trail: %s",
			t.Name(), want, method, len(rows), auditSummary(env))
	}
	return rows
}

// auditSummary renders the whole trail compactly for failure messages.
func auditSummary(env *Env) string {
	var parts []string
	for _, r := range env.Audit.Calls() {
		s := r.Method + " -> outcome=" + r.Outcome
		if r.StatusCode != 0 {
			s += " http=" + itoaInt(r.StatusCode)
		}
		parts = append(parts, s)
	}
	return strings.Join(parts, "\n  ")
}

// requestPaths renders the observed wire request paths for failure messages.
func requestPaths(env *Env) string {
	var parts []string
	for _, rq := range env.Fake.RecordedRequests() {
		parts = append(parts, rq.Method+" "+rq.Path)
	}
	return strings.Join(parts, ", ")
}

func itoaInt(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
