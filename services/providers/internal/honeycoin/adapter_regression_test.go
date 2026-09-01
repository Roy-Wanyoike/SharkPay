package honeycoin

// Regression tests for the two money-path findings the WP-4 conformance
// suite documented (report 20 §5):
//
//  1. Audit rows must reflect reality: a 202 "success" without a transfer
//     id is a protocol violation and must be audited as FAILURE (the old
//     row said success while the inline comment claimed failure).
//  2. Audit rendering must be integer-faithful: redact() may never
//     round-trip numbers through float64 — 2^53+1 must render as
//     9007199254740993, not 9007199254740992.
//
// They drive the real adapter over HTTP against a minimal inline fake;
// the full conformance matrix lives in tests/providers.

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/health"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/store"
)

// newWireAdapter wires the adapter against an inline HoneyCoin wire fake
// and returns the adapter plus its in-memory audit store for inspection.
func newWireAdapter(t *testing.T, handler http.HandlerFunc) (*Adapter, *store.MemoryStore) {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	audit := store.NewMemoryStore()
	a, err := New(Config{
		BaseURL:    srv.URL,
		SigningKey: []byte("regression-test-key"),
		Timeout:    2 * time.Second,
		Breaker:    health.NewBreaker(ProviderName, health.Config{}),
		Audit:      audit,
	})
	if err != nil {
		t.Fatalf("wiring regression adapter: %v", err)
	}
	return a, audit
}

func transferJSON(id string, amountMinor int64) string {
	return fmt.Sprintf(`{"id":%q,"status":"PENDING","amount_minor":%d,"currency":"KES","exponent":2}`, id, amountMinor)
}

// Control: the happy 202 path still returns the ref and audits success.
func TestInitiateHappyPathAuditsSuccess(t *testing.T) {
	a, audit := newWireAdapter(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		fmt.Fprint(w, transferJSON("hct_reg_ok", 150000))
	})
	ref, err := a.Initiate(context.Background(), provider.InitiateRequest{
		TransactionKey: "tx-reg-ok",
		Amount:         provider.Money{AmountMinor: 150000, Currency: "KES", Exponent: 2},
		Rail:           ProviderName,
		Destination:    provider.Destination{Type: "msisdn"},
	})
	if err != nil || ref.Ref != "hct_reg_ok" {
		t.Fatalf("happy initiate: ref=%+v err=%v", ref, err)
	}
	rows := audit.Calls()
	if len(rows) != 1 || rows[0].Outcome != provider.OutcomeSuccess {
		t.Fatalf("happy initiate audit rows = %+v, want one success row", rows)
	}
}

// BUG 1 regression: a 202 without a transfer id fails closed AND the audit
// row records the protocol violation as failure (status 202 stays as the
// forensic wire fact). Previously the row said success — the trail lied.
func TestInitiateEmptyTransferIDAuditsFailure(t *testing.T) {
	a, audit := newWireAdapter(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		fmt.Fprint(w, transferJSON("", 150000))
	})
	_, err := a.Initiate(context.Background(), provider.InitiateRequest{
		TransactionKey: "tx-reg-empty-id",
		Amount:         provider.Money{AmountMinor: 150000, Currency: "KES", Exponent: 2},
		Rail:           ProviderName,
		Destination:    provider.Destination{Type: "msisdn"},
	})
	if err == nil || !strings.Contains(err.Error(), "empty transfer id") {
		t.Fatalf("202 without an id must fail closed with the violation named, got: %v", err)
	}
	rows := audit.Calls()
	if len(rows) != 1 {
		t.Fatalf("exactly one audit row (one wire exchange), got %d", len(rows))
	}
	if rows[0].Outcome != provider.OutcomeFailure {
		t.Fatalf("BUG 1 regression: audit outcome = %q, want %q (the row must reflect the violation, not the 2xx)", rows[0].Outcome, provider.OutcomeFailure)
	}
	if rows[0].StatusCode != http.StatusAccepted {
		t.Fatalf("audit status code = %d, want 202 (the wire DID answer 202 — that is the forensic fact)", rows[0].StatusCode)
	}
}

// BUG 1 regression on the reverse path: same contract, same truthful row.
func TestReverseEmptyReversalIDAuditsFailure(t *testing.T) {
	a, audit := newWireAdapter(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		fmt.Fprint(w, transferJSON("", 150000))
	})
	_, err := a.Reverse(context.Background(), provider.ProviderRef{Provider: ProviderName, Ref: "hct_reg_rev"})
	if err == nil || !strings.Contains(err.Error(), "empty transfer id") {
		t.Fatalf("202 without a reversal id must fail closed, got: %v", err)
	}
	rows := audit.Calls()
	if len(rows) != 1 || rows[0].Outcome != provider.OutcomeFailure {
		t.Fatalf("reverse audit rows = %+v, want exactly one failure row (audit rows must not lie)", rows)
	}
}

// BUG 2 regression (adapter level): 2^53+1 minor units cross the wire and
// the audit rendering keeps the exact integer literal in both the request
// and the response rows.
func TestAuditRenderingKeepsFloatUnsafeAmountsExact(t *testing.T) {
	const floatUnsafe = int64(9007199254740993) // 2^53 + 1
	a, audit := newWireAdapter(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		fmt.Fprint(w, transferJSON("hct_reg_2p53", floatUnsafe))
	})
	_, err := a.Initiate(context.Background(), provider.InitiateRequest{
		TransactionKey: "tx-reg-2p53",
		Amount:         provider.Money{AmountMinor: floatUnsafe, Currency: "KES", Exponent: 2},
		Rail:           ProviderName,
		Destination:    provider.Destination{Type: "msisdn"},
	})
	if err != nil {
		t.Fatalf("initiate with a float-unsafe amount failed: %v", err)
	}
	rows := audit.Calls()
	if len(rows) != 1 {
		t.Fatalf("one audit row, got %d", len(rows))
	}
	want := fmt.Sprintf(`"amount_minor":%d`, floatUnsafe)
	if !strings.Contains(rows[0].Request, want) {
		t.Fatalf("BUG 2 regression: audit request rendering %s must contain %s (integer fidelity lost)", rows[0].Request, want)
	}
	if !strings.Contains(rows[0].Response, want) {
		t.Fatalf("BUG 2 regression: audit response rendering %s must contain %s (integer fidelity lost)", rows[0].Response, want)
	}
}

// BUG 2 regression (unit level): redact keeps integer literals exact —
// including negatives — while still masking secrets.
func TestRedactIntegerFidelityAndSecretMasking(t *testing.T) {
	out := redact([]byte(`{"amount_minor":9007199254740993,"token":"topsecret","nested":{"fee_minor":9007199254740994}}`))
	if !strings.Contains(out, `"amount_minor":9007199254740993`) {
		t.Fatalf("BUG 2 regression: amount above 2^53 must render exactly, got: %s", out)
	}
	if !strings.Contains(out, `"fee_minor":9007199254740994`) {
		t.Fatalf("BUG 2 regression: nested fee above 2^53 must render exactly, got: %s", out)
	}
	if strings.Contains(out, "topsecret") {
		t.Fatalf("redaction must still mask secrets, got: %s", out)
	}
	if got := redact([]byte(`{"amount_minor":-9007199254740993}`)); !strings.Contains(got, `"amount_minor":-9007199254740993`) {
		t.Fatalf("negative float-unsafe amount must render exactly, got: %s", got)
	}
}
