package conformance

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers"
)

// moneyScenarios proves money safety on the wire: minor-unit int64 values
// survive every round-trip with ZERO float involvement (DATA-MODEL §1 "no
// floats, no NUMERIC, ever"). JSON number tokens are parsed with
// json.Number and asserted to be pure integer literals.
var moneyScenarios = []scenario{
	{
		name: "money-minor-units-round-trip-as-integer-literals",
		doc:  "The 150000 KES amount crosses the wire and the audit trail as the exact integer literal 150000 — no decimal point, no exponent notation, no re-scaling.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-money")

			// The raw wire request the fake observed.
			reqs := env.Fake.RecordedRequests()
			if len(reqs) != 1 {
				t.Fatalf("expected 1 wire request, got %d", len(reqs))
			}
			wireReq, err := decodeUseNumber(reqs[0].Body)
			if err != nil {
				t.Fatalf("wire request is not the contract JSON: %v (body %s)", err, reqs[0].Body)
			}
			if got := requireIntegerLiteral(t, wireReq, "amount_minor", "wire request"); got != CannedAmountMinor {
				t.Fatalf("wire request amount_minor = %d, want %d (sent value must cross unchanged)", got, CannedAmountMinor)
			}

			// The audit trail's rendering of the exchange.
			rows := requireRows(t, env, "Initiate", 1)
			auditResp, err := decodeUseNumber([]byte(rows[0].Response))
			if err != nil {
				t.Fatalf("audit response is not JSON: %v (response %q)", err, rows[0].Response)
			}
			if got := requireIntegerLiteral(t, auditResp, "amount_minor", "audit response"); got != CannedAmountMinor {
				t.Fatalf("audit response amount_minor = %d, want %d", got, CannedAmountMinor)
			}
			auditReq, err := decodeUseNumber([]byte(rows[0].Request))
			if err != nil {
				t.Fatalf("audit request is not JSON: %v (request %q)", err, rows[0].Request)
			}
			if got := requireIntegerLiteral(t, auditReq, "amount_minor", "audit request"); got != CannedAmountMinor {
				t.Fatalf("audit request amount_minor = %d, want %d", got, CannedAmountMinor)
			}

			// The provider-side record.
			tr, ok := env.Fake.Transfer(ref.Ref)
			if !ok || tr.AmountMinor != CannedAmountMinor || tr.Currency != CannedCurrency {
				t.Fatalf("fake-side transfer = %+v, want amount %d %s (no drift anywhere on the wire)", tr, CannedAmountMinor, CannedCurrency)
			}
		},
	},
	{
		name: "money-float-unsafe-amounts-survive-exactly",
		doc:  "2^53+1 (9007199254740993 minor units — one unit PAST float64's exact integer range) survives initiate (wire request + provider record) and Quote/ReconcileReport responses as the exact int64 — a float anywhere on these paths would corrupt it by 1.",
		run: func(t *testing.T) {
			const floatUnsafe = int64(9007199254740993) // 2^53 + 1
			env := newEnv(t)

			// Initiate path: the raw wire request and the provider-side record.
			ref, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-float-unsafe",
				Amount:         providers.Money{AmountMinor: floatUnsafe, Currency: "KES", Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn", Details: map[string]string{"msisdn": "+254700000001"}},
			})
			if err != nil {
				t.Fatalf("initiate with a float-unsafe amount failed: %v", err)
			}
			reqs := env.Fake.RecordedRequests()
			wireReq, err := decodeUseNumber(reqs[0].Body)
			if err != nil {
				t.Fatalf("wire request is not JSON: %v", err)
			}
			if got := requireIntegerLiteral(t, wireReq, "amount_minor", "wire request"); got != floatUnsafe {
				t.Fatalf("wire request amount_minor = %d, want %d — ANY float on the path would render 9007199254740992", got, floatUnsafe)
			}
			tr, ok := env.Fake.Transfer(ref.Ref)
			if !ok || tr.AmountMinor != floatUnsafe {
				t.Fatalf("provider record amount = %d (found %v), want %d", tr.AmountMinor, ok, floatUnsafe)
			}

			// Audit path (strengthened after the redact() UseNumber fix): the
			// audit rendering keeps the exact integer literal — any float64
			// round-trip would render 9007199254740992.
			rows := requireRows(t, env, "Initiate", 1)
			wantLit := `"amount_minor":9007199254740993`
			if !strings.Contains(rows[0].Request, wantLit) || !strings.Contains(rows[0].Response, wantLit) {
				t.Fatalf("audit rendering lost integer fidelity: request %s / response %s must contain %s", rows[0].Request, rows[0].Response, wantLit)
			}

			// Quote path: adapter-parsed response values must be exact.
			quote, err := env.Adapter.Quote(context.Background(), providers.QuoteRequest{
				Amount:      providers.Money{AmountMinor: floatUnsafe, Currency: "KES", Exponent: 2},
				Rail:        "honeycoin",
				Destination: providers.Destination{Type: "msisdn"},
			})
			if err != nil {
				t.Fatalf("quote with a float-unsafe amount failed: %v", err)
			}
			if quote.Receive.AmountMinor != floatUnsafe {
				t.Fatalf("quote receive = %d, want %d (int64 decode, not float64)", quote.Receive.AmountMinor, floatUnsafe)
			}
			if quote.Debit.AmountMinor != quote.Receive.AmountMinor+quote.Fee.AmountMinor {
				t.Fatalf("quote arithmetic must stay in int64: debit %d != receive %d + fee %d", quote.Debit.AmountMinor, quote.Receive.AmountMinor, quote.Fee.AmountMinor)
			}

			// Reconciliation path: canned line with the float-unsafe amount.
			now := time.Now()
			env.Fake.SetReconLines([]ReconLine{{
				ID: "hct_stub_000099", Status: "CONFIRMED", AmountMinor: floatUnsafe, FeeMinor: 77,
				Currency: "KES", Exponent: 2, OccurredAt: now,
			}})
			lines, err := env.Adapter.ReconcileReport(context.Background(), providers.Window{From: now.Add(-time.Minute), To: now.Add(time.Minute)})
			if err != nil {
				t.Fatalf("reconcile with a float-unsafe amount failed: %v", err)
			}
			if len(lines) != 1 || lines[0].Amount.AmountMinor != floatUnsafe || lines[0].Fee.AmountMinor != 77 {
				t.Fatalf("recon line amount/fee = %d/%d, want %d/77 — ledger agreement checks would break on any drift", lines[0].Amount.AmountMinor, lines[0].Fee.AmountMinor, floatUnsafe)
			}
		},
	},
	{
		name: "money-quote-economics-hold-exactly-in-int64",
		doc:  "Debit = Receive + Fee must hold exactly in int64 minor units for a range of amounts (including fee-carrying odd amounts where integer division rounds).",
		run: func(t *testing.T) {
			env := newEnv(t)
			for _, amount := range []int64{1, 3, 100, CannedAmountMinor, 123456789, 999999999999} {
				quote, err := env.Adapter.Quote(context.Background(), providers.QuoteRequest{
					Amount:      providers.Money{AmountMinor: amount, Currency: "KES", Exponent: 2},
					Rail:        "honeycoin",
					Destination: providers.Destination{Type: "msisdn"},
				})
				if err != nil {
					t.Fatalf("quote(%d) failed: %v", amount, err)
				}
				if quote.Debit.AmountMinor != quote.Receive.AmountMinor+quote.Fee.AmountMinor {
					t.Fatalf("quote(%d): Debit(%d) != Receive(%d) + Fee(%d) — a cent-level leak in the quote economics", amount, quote.Debit.AmountMinor, quote.Receive.AmountMinor, quote.Fee.AmountMinor)
				}
				if quote.Receive.AmountMinor != amount {
					t.Fatalf("quote(%d): receive = %d (the quoted amount must round-trip exactly)", amount, quote.Receive.AmountMinor)
				}
				if quote.Fee.AmountMinor < 0 {
					t.Fatalf("quote(%d): fee = %d, must never be negative", amount, quote.Fee.AmountMinor)
				}
			}
		},
	},
	{
		name: "money-reconcile-amounts-and-fees-exact",
		doc:  "Every reconciliation line's amount AND fee cross as exact int64s — the recon service compares these against ledger postings; any drift would be a false break.",
		run: func(t *testing.T) {
			env := newEnv(t)
			want := []ReconLine{
				{ID: "hct_stub_000001", Status: "CONFIRMED", AmountMinor: 150000, FeeMinor: 775, Currency: "KES", Exponent: 2},
				{ID: "hct_stub_000002", Status: "FAILED", AmountMinor: 1, FeeMinor: 0, Currency: "KES", Exponent: 2},
				{ID: "hct_stub_000003", Status: "PENDING", AmountMinor: 4294967296, FeeMinor: 2147483648, Currency: "USD", Exponent: 2},
			}
			env.Fake.SetReconLines(want)
			lines, err := env.Adapter.ReconcileReport(context.Background(), providers.Window{From: time.Now().Add(-time.Hour), To: time.Now().Add(time.Hour)})
			if err != nil {
				t.Fatalf("reconcile failed: %v", err)
			}
			if len(lines) != len(want) {
				t.Fatalf("recon lines = %d, want %d", len(lines), len(want))
			}
			for i, w := range want {
				got := lines[i]
				if got.Amount.AmountMinor != w.AmountMinor || got.Fee.AmountMinor != w.FeeMinor {
					t.Fatalf("recon line %s: amount/fee = %d/%d, want %d/%d (ledger↔provider agreement is exact or it is meaningless)", w.ID, got.Amount.AmountMinor, got.Fee.AmountMinor, w.AmountMinor, w.FeeMinor)
				}
				if got.Amount.Currency != w.Currency || got.Amount.Exponent != w.Exponent {
					t.Fatalf("recon line %s: currency/exponent = %s/%d, want %s/%d", w.ID, got.Amount.Currency, got.Amount.Exponent, w.Currency, w.Exponent)
				}
			}
		},
	},
	{
		name: "money-negative-amount-rejected-before-the-wire",
		doc:  "A negative amount fails provider.Money validation locally: error surfaced, ZERO wire traffic, ZERO audit rows — garbage money never reaches a provider.",
		run: func(t *testing.T) {
			env := newEnv(t)
			_, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-negative",
				Amount:         providers.Money{AmountMinor: -1, Currency: "KES", Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if err == nil {
				t.Fatal("a negative amount must be rejected (money invariants are enforced before the wire)")
			}
			if !strings.Contains(err.Error(), "negative") {
				t.Fatalf("the error should name the invariant, got: %v", err)
			}
			if got := env.Fake.TotalRequests(); got != 0 {
				t.Fatalf("wire requests = %d, want 0 (invalid money never leaves the building)", got)
			}
			if got := len(env.Audit.Calls()); got != 0 {
				t.Fatalf("audit rows = %d, want 0", got)
			}
		},
	},
}

func TestMoneySafetyScenarios(t *testing.T) {
	runScenarios(t, moneyScenarios)
}

// decodeUseNumber parses JSON keeping numbers as json.Number (no float64
// conversion anywhere in the assertion path).
func decodeUseNumber(body []byte) (map[string]any, error) {
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	var m map[string]any
	if err := dec.Decode(&m); err != nil {
		return nil, err
	}
	return m, nil
}

// requireIntegerLiteral asserts doc[key] is a JSON number literal with no
// decimal point or exponent (proving no float serialization) and returns
// its exact int64 value.
func requireIntegerLiteral(t *testing.T, doc map[string]any, key, context string) int64 {
	t.Helper()
	v, ok := doc[key]
	if !ok {
		t.Fatalf("%s: field %q missing (contract shape changed?): %v", context, key, doc)
	}
	num, ok := v.(json.Number)
	if !ok {
		t.Fatalf("%s: field %q is %T (%v), want a JSON number literal", context, key, v, v)
	}
	s := num.String()
	if strings.ContainsAny(s, ".eE") {
		t.Fatalf("%s: field %q = %s — float notation on the wire (FORBIDDEN: money is integer minor units)", context, key, s)
	}
	i, err := num.Int64()
	if err != nil {
		t.Fatalf("%s: field %q = %s does not fit int64: %v", context, key, s, err)
	}
	return i
}
