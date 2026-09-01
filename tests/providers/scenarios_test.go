package conformance

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers"
)

// conformanceScenarios is the WP-4 core scenario table: happy paths,
// idempotency, failure injection, circuit breaker behavior and the audit
// trail — all driven over the real wire (HTTP) against the fake HoneyCoin.
//
// Each row names one wire behavior and the exact expectation; failures
// report what the wire did, what was expected and what was observed.
var conformanceScenarios = []scenario{
	// -------------------------------------------------------------------
	// Happy paths.
	// -------------------------------------------------------------------
	{
		name: "happy-initiate-returns-ref-idempotency-header-and-audit",
		doc:  "POST /v1/transfers answers 202 with the mapping's canned body; the adapter must return the transfer ref, send X-Idempotency-Key = our transaction key, sign the request correctly (independent verification), and audit the call as success before returning.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-0001")
			if ref.Provider != "honeycoin" || ref.Ref != CannedTransferID {
				t.Fatalf("initiate: expected provider %q ref %q (wire-contract canned id), got %q/%q",
					"honeycoin", CannedTransferID, ref.Provider, ref.Ref)
			}
			reqs := env.Fake.RecordedRequests()
			if len(reqs) != 1 {
				t.Fatalf("initiate: expected exactly 1 wire request, got %d", len(reqs))
			}
			rq := reqs[0]
			if rq.Method != "POST" || rq.Path != PathTransfers {
				t.Fatalf("initiate wire call: expected POST %s, got %s %s", PathTransfers, rq.Method, rq.Path)
			}
			if rq.IdempotencyKey != "tx-0001" {
				t.Fatalf("initiate wire call: X-Idempotency-Key = %q, want the transaction key %q (SECURITY §4 adapter-level idempotency)", rq.IdempotencyKey, "tx-0001")
			}
			if !rq.SignatureValid {
				t.Fatalf("initiate wire call: request signature failed independent verification (headers %s/%s)", HeaderRequestTimestamp, HeaderRequestSignature)
			}
			var sent struct {
				AmountMinor int64  `json:"amount_minor"`
				Currency    string `json:"currency"`
			}
			if err := json.Unmarshal(rq.Body, &sent); err != nil {
				t.Fatalf("initiate wire call: request body not the contract shape: %v (body %s)", err, rq.Body)
			}
			if sent.AmountMinor != CannedAmountMinor || sent.Currency != CannedCurrency {
				t.Fatalf("initiate wire call: sent amount_minor/currency = %d/%q, want %d/%q (no re-scaling on the wire)", sent.AmountMinor, sent.Currency, CannedAmountMinor, CannedCurrency)
			}
			rows := requireRows(t, env, "Initiate", 1)
			r := rows[0]
			if r.Outcome != providers.OutcomeSuccess {
				t.Fatalf("initiate audit: outcome = %q, want %q (202 is a success)", r.Outcome, providers.OutcomeSuccess)
			}
			if r.StatusCode != 202 {
				t.Fatalf("initiate audit: status_code = %d, want 202 (Accepted, per wiremock mapping)", r.StatusCode)
			}
			if r.Provider != "honeycoin" || r.HTTPMethod != "POST" || r.Path != PathTransfers {
				t.Fatalf("initiate audit: provider/method/path = %q/%q/%q, want honeycoin/POST/%s", r.Provider, r.HTTPMethod, r.Path, PathTransfers)
			}
			if r.ID == "" || r.StartedAt.IsZero() {
				t.Fatal("initiate audit: row must carry an id and start time for forensics")
			}
			if strings.Contains(r.Request+r.Response, rq.Signature) {
				t.Fatal("initiate audit: the wire signature hex must never enter the audit trail (headers are not audited; SECURITY §1)")
			}
		},
	},
	{
		name: "happy-poll-maps-every-wire-status-including-unknown",
		doc:  "GET /v1/transfers/{ref} wire statuses map through the documented HoneyCoin table; unmapped statuses must return StatusUnknown with NO error (ambiguity is a state: park + alert, never auto-retry).",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-poll")
			for _, c := range []struct {
				wire string
				want providers.TransferStatus
			}{
				{"PENDING", providers.StatusPending},
				{"PROCESSING", providers.StatusProcessing},
				{"CONFIRMED", providers.StatusSucceeded},
				{"FAILED", providers.StatusFailed},
				{"REVERSED", providers.StatusReturned},
				{"RETURNED", providers.StatusReturned},
				{"SOME_WEIRD_STATE", providers.StatusUnknown},
				{"", providers.StatusUnknown},
			} {
				label := c.wire
				if label == "" {
					label = "(empty)"
				}
				t.Run("wire-status-"+label, func(t *testing.T) {
					if ok := env.Fake.SetTransferStatus(ref.Ref, c.wire); !ok {
						t.Fatalf("fake lost transfer %q between polls", ref.Ref)
					}
					st, err := env.Adapter.Poll(context.Background(), ref)
					if err != nil {
						t.Fatalf("Poll of wire status %q must not error (UNKNOWN is a state, not an error): %v", c.wire, err)
					}
					if st != c.want {
						t.Fatalf("wire status %q must map to %s (HoneyCoin status table), got %s — an unmapped status MUST surface as UNKNOWN, never a guess", c.wire, c.want, st)
					}
				})
			}
		},
	},
	{
		name: "happy-full-lifecycle-initiate-poll-callback-terminal",
		doc:  "The end-to-end happy flow: initiate (PENDING) → poll (PROCESSING) → verified callback (CONFIRMED ⇒ SUCCEEDED) → poll confirms the terminal state; the audit trail records every step in order.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-lifecycle")

			st, err := env.Adapter.Poll(context.Background(), ref)
			if err != nil || st != providers.StatusPending {
				t.Fatalf("poll #1 after initiate: expected PENDING, got %s (err %v)", st, err)
			}
			if ok := env.Fake.SetTransferStatus(ref.Ref, "PROCESSING"); !ok {
				t.Fatalf("fake lost transfer %q", ref.Ref)
			}
			st, err = env.Adapter.Poll(context.Background(), ref)
			if err != nil || st != providers.StatusProcessing {
				t.Fatalf("poll #2 after rail moved to PROCESSING: expected PROCESSING, got %s (err %v)", st, err)
			}
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{})
			st, err = env.Adapter.HandleCallback(context.Background(), cb)
			if err != nil {
				t.Fatalf("verified settlement callback must apply, got error: %v", err)
			}
			if st != providers.StatusSucceeded {
				t.Fatalf("callback CONFIRMED must map to SUCCEEDED, got %s", st)
			}
			if ok := env.Fake.SetTransferStatus(ref.Ref, "CONFIRMED"); !ok {
				t.Fatalf("fake lost transfer %q", ref.Ref)
			}
			st, err = env.Adapter.Poll(context.Background(), ref)
			if err != nil || st != providers.StatusSucceeded {
				t.Fatalf("terminal poll: expected SUCCEEDED, got %s (err %v)", st, err)
			}

			want := []string{"Initiate", "Poll", "Poll", "HandleCallback", "Poll"}
			var got []string
			for _, r := range env.Audit.Calls() {
				got = append(got, r.Method)
			}
			if strings.Join(got, ",") != strings.Join(want, ",") {
				t.Fatalf("lifecycle audit order = [%s], want [%s] (every exchange audited before return)", strings.Join(got, ","), strings.Join(want, ","))
			}
		},
	},
	{
		name: "quote-happy-path",
		doc:  "POST /v1/quotes returns a quote whose economics hold exactly in int64 minor units (Debit = Receive + Fee), with a future expiry and a success audit row.",
		run: func(t *testing.T) {
			env := newEnv(t)
			quote, err := env.Adapter.Quote(context.Background(), providers.QuoteRequest{
				Amount:      providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:        "honeycoin",
				Destination: providers.Destination{Type: "msisdn"},
			})
			if err != nil {
				t.Fatalf("Quote failed on a healthy wire: %v", err)
			}
			if quote.ID == "" {
				t.Fatal("quote id must not be empty")
			}
			if quote.Debit.AmountMinor != quote.Receive.AmountMinor+quote.Fee.AmountMinor {
				t.Fatalf("quote economics broken: Debit(%d) != Receive(%d) + Fee(%d) — must hold exactly in minor units", quote.Debit.AmountMinor, quote.Receive.AmountMinor, quote.Fee.AmountMinor)
			}
			if quote.Receive.AmountMinor != CannedAmountMinor || quote.Receive.Currency != CannedCurrency || quote.Receive.Exponent != 2 {
				t.Fatalf("quote receive = %+v, want amount %d %s^%d (minor units survive the round-trip)", quote.Receive, CannedAmountMinor, CannedCurrency, 2)
			}
			if !quote.ExpiresAt.After(time.Now()) {
				t.Fatalf("quote expiry %v must be in the future", quote.ExpiresAt)
			}
			rows := requireRows(t, env, "Quote", 1)
			if rows[0].Outcome != providers.OutcomeSuccess {
				t.Fatalf("quote audit outcome = %q, want success", rows[0].Outcome)
			}
			if reqs := env.Fake.RecordedRequests(); len(reqs) != 1 || !reqs[0].SignatureValid {
				t.Fatalf("quote wire call must be signed: valid=%v count=%d", len(reqs) > 0 && reqs[0].SignatureValid, len(reqs))
			}
		},
	},
	{
		name: "quote-unsupported-currency-rejected-locally-without-wire-traffic",
		doc:  "A structurally valid but unsupported currency (XYZ) must fail fast locally with provider.ErrUnsupportedCurrency — no wire call, no audit row (nothing reached the provider).",
		run: func(t *testing.T) {
			env := newEnv(t)
			_, err := env.Adapter.Quote(context.Background(), providers.QuoteRequest{
				Amount:      providers.Money{AmountMinor: 1000, Currency: "XYZ", Exponent: 2},
				Rail:        "honeycoin",
				Destination: providers.Destination{Type: "msisdn"},
			})
			if !errors.Is(err, providers.ErrUnsupportedCurrency) {
				t.Fatalf("unsupported currency must surface provider.ErrUnsupportedCurrency (callers match with errors.Is), got: %v", err)
			}
			if got := env.Fake.TotalRequests(); got != 0 {
				t.Fatalf("fast-fail violated: %d wire requests were sent for an unsupported currency, want 0", got)
			}
			if rows := auditRows(t, env, "Quote"); len(rows) != 0 {
				t.Fatalf("unsupported currency must be rejected before any audited provider call, got %d Quote audit rows", len(rows))
			}
		},
	},
	{
		name: "happy-callback-verified-and-status-mapped",
		doc:  "A correctly signed, fresh, non-replayed callback (X-SharkPay-Timestamp/X-SharkPay-Signature over the raw body) applies and maps its status; the verification result is audited as success.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-cb")
			cb := env.Fake.MakeCallback(ref.Ref, "PROCESSING", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{})
			st, err := env.Adapter.HandleCallback(context.Background(), cb)
			if err != nil {
				t.Fatalf("correctly signed callback must verify: %v", err)
			}
			if st != providers.StatusProcessing {
				t.Fatalf("callback status PROCESSING must map to PROCESSING, got %s", st)
			}
			rows := requireRows(t, env, "HandleCallback", 1)
			if rows[0].Outcome != providers.OutcomeSuccess {
				t.Fatalf("verified callback audit outcome = %q, want success", rows[0].Outcome)
			}
			if !strings.Contains(rows[0].Response, "PROCESSING") {
				t.Fatalf("verified callback audit must record the applied status, got response %q", rows[0].Response)
			}
		},
	},
	{
		name: "cancel-path-uses-derived-idempotency-key",
		doc:  "POST /v1/transfers/{ref}/cancel cancels an unsettled transfer; the adapter must send X-Idempotency-Key = \"cancel:\"+ref (SECURITY §4 key derivation); a subsequent Poll of the cancelled transfer is UNKNOWN (CANCELLED is not in the launch status set).",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-cancel")
			if err := env.Adapter.Cancel(context.Background(), ref); err != nil {
				t.Fatalf("Cancel on a healthy wire failed: %v", err)
			}
			tr, ok := env.Fake.Transfer(ref.Ref)
			if !ok || tr.Status != "CANCELLED" {
				t.Fatalf("fake transfer after cancel = %+v (found %v), want status CANCELLED at the provider", tr, ok)
			}
			wantKey := "cancel:" + ref.Ref
			seenKey, found := "", false
			for _, rq := range env.Fake.RecordedRequests() {
				if rq.Path == PathTransfers+"/"+ref.Ref+"/cancel" {
					seenKey, found = rq.IdempotencyKey, true
					break
				}
			}
			if !found {
				t.Fatalf("cancel wire call %s not observed; requests seen: %s", PathTransfers+"/"+ref.Ref+"/cancel", requestPaths(env))
			}
			if seenKey != wantKey {
				t.Fatalf("cancel wire call: X-Idempotency-Key = %q, want derived key %q", seenKey, wantKey)
			}
			st, err := env.Adapter.Poll(context.Background(), ref)
			if err != nil || st != providers.StatusUnknown {
				t.Fatalf("poll after cancel: expected UNKNOWN (wire CANCELLED is unmapped — park + alert), got %s (err %v)", st, err)
			}
			rows := requireRows(t, env, "Cancel", 1)
			if rows[0].Outcome != providers.OutcomeSuccess {
				t.Fatalf("cancel audit outcome = %q, want success", rows[0].Outcome)
			}
		},
	},
	{
		name: "reverse-path-returns-reversal-ref-and-is-idempotent",
		doc:  "POST /v1/transfers/{ref}/reverse creates a NEW reversal transfer (reverses = original), returns its ref, and is idempotent: the second Reverse with the same derived key returns the SAME reversal ref with no additional upstream effect.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-reverse")
			rev, err := env.Adapter.Reverse(context.Background(), ref)
			if err != nil {
				t.Fatalf("Reverse on a healthy wire failed: %v", err)
			}
			if rev.Ref == "" || rev.Ref == ref.Ref || rev.Provider != "honeycoin" {
				t.Fatalf("Reverse must return a NEW reversal ref, got %+v (original %q)", rev, ref.Ref)
			}
			tr, ok := env.Fake.Transfer(rev.Ref)
			if !ok || tr.Reverses != ref.Ref {
				t.Fatalf("reversal transfer %q must record reverses=%q, got %+v (found %v)", rev.Ref, ref.Ref, tr, ok)
			}
			rev2, err := env.Adapter.Reverse(context.Background(), ref)
			if err != nil {
				t.Fatalf("second Reverse (same derived key) must succeed idempotently: %v", err)
			}
			if rev2.Ref != rev.Ref {
				t.Fatalf("second Reverse returned %q, want the SAME reversal ref %q (no double reversal)", rev2.Ref, rev.Ref)
			}
			if got, want := env.Fake.Effects(), 2; got != want {
				t.Fatalf("upstream effects = %d, want %d (1 initiate + 1 reversal — the retried Reverse must not create another)", got, want)
			}
			wantKey := "reverse:" + ref.Ref
			for _, rq := range env.Fake.RecordedRequests() {
				if rq.Path == PathTransfers+"/"+ref.Ref+"/reverse" && rq.IdempotencyKey != wantKey {
					t.Fatalf("reverse wire call: X-Idempotency-Key = %q, want derived key %q", rq.IdempotencyKey, wantKey)
				}
			}
			requireRows(t, env, "Reverse", 2)
		},
	},
	{
		name: "reconcile-report-maps-lines-and-flags-unknown-statuses",
		doc:  "POST /v1/reports/reconciliation returns window-filtered lines; mapped statuses become clean TransferStatus values, unmapped statuses become UNKNOWN lines (reconciliation treats them as breaks), and amounts/fees survive as exact int64s.",
		run: func(t *testing.T) {
			env := newEnv(t)
			now := time.Now()
			env.Fake.SetReconLines([]ReconLine{
				{ID: "hct_stub_000010", Status: "CONFIRMED", AmountMinor: 200000, FeeMinor: 1000, Currency: "KES", Exponent: 2, OccurredAt: now},
				{ID: "hct_stub_000011", Status: "SOME_WEIRD_STATE", AmountMinor: 50000, FeeMinor: 250, Currency: "KES", Exponent: 2, OccurredAt: now},
				{ID: "hct_stub_000012", Status: "FAILED", AmountMinor: 999, FeeMinor: 1, Currency: "KES", Exponent: 2, OccurredAt: now.Add(-2 * time.Hour)}, // outside the window
				{ID: "hct_stub_000013", Status: "REVERSED", AmountMinor: 75000, FeeMinor: 375, Currency: "KES", Exponent: 2},                                // zero time: always reported
			})
			lines, err := env.Adapter.ReconcileReport(context.Background(), providers.Window{
				From: now.Add(-time.Hour),
				To:   now.Add(time.Hour),
			})
			if err != nil {
				t.Fatalf("ReconcileReport on a healthy wire failed: %v", err)
			}
			if len(lines) != 3 {
				t.Fatalf("reconcile lines = %d, want 3 (window excludes the 2h-old line, zero-time lines always report); got %+v", len(lines), lines)
			}
			byRef := map[string]providers.ProviderLine{}
			for _, l := range lines {
				byRef[l.Ref] = l
			}
			c, ok := byRef["hct_stub_000010"]
			if !ok || c.Status != providers.StatusSucceeded {
				t.Fatalf("CONFIRMED line missing or mis-mapped: %+v", c)
			}
			if c.Amount.AmountMinor != 200000 || c.Fee.AmountMinor != 1000 {
				t.Fatalf("recon amounts must survive exactly: amount=%d fee=%d, want 200000/1000", c.Amount.AmountMinor, c.Fee.AmountMinor)
			}
			if u, ok := byRef["hct_stub_000011"]; !ok || u.Status != providers.StatusUnknown {
				t.Fatalf("unmapped wire status must surface as an UNKNOWN line (a recon break, never a guess): %+v", u)
			}
			if r, ok := byRef["hct_stub_000013"]; !ok || r.Status != providers.StatusReturned {
				t.Fatalf("REVERSED line missing or mis-mapped (want RETURNED): %+v", r)
			}
			requireRows(t, env, "ReconcileReport", 1)
		},
	},
	{
		name: "audit-trail-records-every-exchange-in-order",
		doc:  "A mixed flow (quote, initiate, poll, cancel + one inbound callback) must produce exactly one audit row per exchange, in order, all success, with complete forensic fields — and no secret material (wire signatures, callback secret) ever at rest.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ctx := context.Background()
			if _, err := env.Adapter.Quote(ctx, providers.QuoteRequest{
				Amount:      providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:        "honeycoin",
				Destination: providers.Destination{Type: "msisdn"},
			}); err != nil {
				t.Fatalf("quote failed: %v", err)
			}
			ref := mustInitiate(t, env, "tx-audit")
			if _, err := env.Adapter.Poll(ctx, ref); err != nil {
				t.Fatalf("poll failed: %v", err)
			}
			if err := env.Adapter.Cancel(ctx, ref); err != nil {
				t.Fatalf("cancel failed: %v", err)
			}
			cb := env.Fake.MakeCallback(ref.Ref, "PROCESSING", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{})
			if _, err := env.Adapter.HandleCallback(ctx, cb); err != nil {
				t.Fatalf("callback failed: %v", err)
			}

			want := []string{"Quote", "Initiate", "Poll", "Cancel", "HandleCallback"}
			var got []string
			for _, r := range env.Audit.Calls() {
				got = append(got, r.Method)
			}
			if strings.Join(got, ",") != strings.Join(want, ",") {
				t.Fatalf("audit order = [%s], want [%s]", strings.Join(got, ","), strings.Join(want, ","))
			}
			for _, r := range env.Audit.Calls() {
				if r.Outcome != providers.OutcomeSuccess {
					t.Errorf("audit row %s: outcome = %q, want success (wire was healthy)", r.Method, r.Outcome)
				}
				if r.Provider != "honeycoin" || r.ID == "" || r.StartedAt.IsZero() {
					t.Errorf("audit row %s: incomplete forensic fields (provider %q id %q started %v)", r.Method, r.Provider, r.ID, r.StartedAt)
				}
			}
			cbRow := auditRows(t, env, "HandleCallback")[0]
			if cbRow.Path != "(inbound callback)" || cbRow.HTTPMethod != "POST" {
				t.Fatalf("inbound callback audit row path/method = %q/%q, want (inbound callback)/POST", cbRow.Path, cbRow.HTTPMethod)
			}
			// 4 wire exchanges + 1 inbound callback.
			if got, want := env.Fake.TotalRequests(), 4; got != want {
				t.Fatalf("wire requests = %d, want %d (callbacks are inbound — no wire traffic)", got, want)
			}
			// Secret hygiene: wire signatures and secrets never at rest.
			for _, rq := range env.Fake.RecordedRequests() {
				for _, r := range env.Audit.Calls() {
					if strings.Contains(r.Request+r.Response, rq.Signature) {
						t.Fatalf("audit row %s contains a wire signature header value — signatures must never be recorded (SECURITY §1)", r.Method)
					}
				}
			}
			for _, r := range env.Audit.Calls() {
				if strings.Contains(r.Request+r.Response, DefaultCallbackSecret) {
					t.Fatalf("audit row %s contains the callback secret", r.Method)
				}
			}
		},
	},

	// -------------------------------------------------------------------
	// Idempotency.
	// -------------------------------------------------------------------
	{
		name: "idempotency-same-key-single-upstream-effect",
		doc:  "Two initiates with the same transaction key return the SAME ref and the provider-side effect happens exactly ONCE (fake's effect counter is the oracle); both retries legitimately reach the wire — dedup is the provider's job.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref1 := mustInitiate(t, env, "tx-idem")
			ref2 := mustInitiate(t, env, "tx-idem")
			if ref1.Ref != ref2.Ref {
				t.Fatalf("same idempotency key must return the original transfer: %q vs %q — a second ref means a double debit risk", ref1.Ref, ref2.Ref)
			}
			if got, want := env.Fake.Effects(), 1; got != want {
				t.Fatalf("upstream effects = %d, want %d (same key ⇒ single money movement)", got, want)
			}
			if got, want := env.Fake.RequestsTo(PathTransfers), 2; got != want {
				t.Fatalf("wire calls = %d, want %d (the retry is sent; the provider dedups)", got, want)
			}
			rows := requireRows(t, env, "Initiate", 2)
			for i, r := range rows {
				if r.Outcome != providers.OutcomeSuccess {
					t.Fatalf("initiate retry #%d audit outcome = %q, want success (both got 202)", i+1, r.Outcome)
				}
			}
		},
	},
	{
		name: "idempotency-distinct-keys-distinct-effects",
		doc:  "Two initiates with DIFFERENT transaction keys create two distinct transfers (effect counter = 2) — the dedup is scoped to the key, not global.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref1 := mustInitiate(t, env, "tx-one")
			ref2 := mustInitiate(t, env, "tx-two")
			if ref1.Ref == ref2.Ref {
				t.Fatalf("distinct idempotency keys must NOT share a transfer ref: both %q", ref1.Ref)
			}
			if got, want := env.Fake.Effects(), 2; got != want {
				t.Fatalf("upstream effects = %d, want %d (distinct keys ⇒ distinct transfers)", got, want)
			}
		},
	},
	{
		name: "idempotency-retry-after-failure-creates-no-double-effect",
		doc:  "A 500-injected initiate fails (no upstream effect is created); retrying the SAME key once the wire is healthy returns a ref with still exactly one effect — the failure path cannot double-spend.",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) { o.Server.StatusCode = 500 })
			if _, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-retry",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			}); err == nil {
				t.Fatal("initiate against an injected HTTP 500 must fail")
			}
			if got := env.Fake.Effects(); got != 0 {
				t.Fatalf("a failed initiate must not create an upstream effect, got %d", got)
			}
			env.Fake.SetStatusCode(0)
			ref := mustInitiate(t, env, "tx-retry")
			if got, want := env.Fake.Effects(), 1; got != want {
				t.Fatalf("upstream effects after retry = %d, want %d", got, want)
			}
			if ref.Ref != CannedTransferID {
				t.Fatalf("retry ref = %q, want %q", ref.Ref, CannedTransferID)
			}
			rows := requireRows(t, env, "Initiate", 2)
			if rows[0].Outcome != providers.OutcomeFailure || rows[1].Outcome != providers.OutcomeSuccess {
				t.Fatalf("audit outcomes = [%s, %s], want [failure, success]", rows[0].Outcome, rows[1].Outcome)
			}
		},
	},
	{
		name: "idempotency-header-contract-on-the-wire",
		doc:  "State-changing calls carry X-Idempotency-Key = our transaction key; reads (Poll) carry NO idempotency header — the wire-level contract for adapter-level idempotency.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-header")
			if _, err := env.Adapter.Poll(context.Background(), ref); err != nil {
				t.Fatalf("poll failed: %v", err)
			}
			reqs := env.Fake.RecordedRequests()
			if len(reqs) != 2 {
				t.Fatalf("expected 2 wire requests (initiate + poll), got %d", len(reqs))
			}
			init, poll := reqs[0], reqs[1]
			if init.IdempotencyKey != "tx-header" {
				t.Fatalf("initiate X-Idempotency-Key = %q, want %q", init.IdempotencyKey, "tx-header")
			}
			if poll.IdempotencyKey != "" {
				t.Fatalf("poll must not carry an idempotency header (read-only call), got %q", poll.IdempotencyKey)
			}
			if !init.SignatureValid || !poll.SignatureValid {
				t.Fatal("every adapter request (state-changing or read) must carry a verifiable signature")
			}
		},
	},

	// -------------------------------------------------------------------
	// Failure injection.
	// -------------------------------------------------------------------
	{
		name: "failure-500-propagates-wire-error-and-counts-against-breaker",
		doc:  "An injected HTTP 500 surfaces as a non-nil error quoting the wire status and code (business errors are NEVER swallowed), the audit row is outcome=failure with status 500, and the breaker failure counter increments (but stays CLOSED below the threshold).",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) { o.Server.StatusCode = 500 })
			_, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-500",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if err == nil {
				t.Fatal("HTTP 500 must surface as an error — swallowing wire failures is a money-safety violation")
			}
			for _, want := range []string{"HTTP 500", "injected_failure"} {
				if !strings.Contains(err.Error(), want) {
					t.Fatalf("500 error must quote %q for diagnosis, got: %v", want, err)
				}
			}
			if errors.Is(err, providers.ErrProviderUnavailable) {
				t.Fatal("a single 500 is a wire failure, not the failover signal ErrProviderUnavailable (breaker is still closed)")
			}
			if got := env.Breaker.FailureCount(); got != 1 {
				t.Fatalf("breaker failure count = %d, want 1 (5xx trips the failure counter)", got)
			}
			if env.Breaker.State() != providers.BreakerClosed {
				t.Fatalf("breaker state = %s, want CLOSED (threshold not reached)", env.Breaker.State())
			}
			rows := requireRows(t, env, "Initiate", 1)
			if rows[0].Outcome != providers.OutcomeFailure || rows[0].StatusCode != 500 {
				t.Fatalf("500 audit row = outcome %q status %d, want failure/500", rows[0].Outcome, rows[0].StatusCode)
			}
		},
	},
	{
		name: "failure-503-propagates-wire-error",
		doc:  "An injected HTTP 503 (classic provider outage) surfaces identically: error quoting HTTP 503, audited as failure, breaker counts one failure.",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) { o.Server.StatusCode = 503 })
			_, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-503",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if err == nil || !strings.Contains(err.Error(), "HTTP 503") {
				t.Fatalf("503 must surface with the wire status in the error, got: %v", err)
			}
			if got := env.Breaker.FailureCount(); got != 1 {
				t.Fatalf("breaker failure count = %d, want 1 (5xx counts)", got)
			}
			rows := requireRows(t, env, "Initiate", 1)
			if rows[0].Outcome != providers.OutcomeFailure || rows[0].StatusCode != 503 {
				t.Fatalf("503 audit row = outcome %q status %d, want failure/503", rows[0].Outcome, rows[0].StatusCode)
			}
		},
	},
	{
		name: "failure-429-business-rejection-does-not-trip-breaker",
		doc:  "An injected HTTP 429 (rate limit) is a business rejection: the error surfaces, the audit row is failure/429, the breaker does NOT trip (4xx proves the provider is alive) and the very next call still flows.",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) { o.Server.StatusCode = 429 })
			_, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-429",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if err == nil || !strings.Contains(err.Error(), "HTTP 429") {
				t.Fatalf("429 must surface with the wire status in the error, got: %v", err)
			}
			if got := env.Breaker.FailureCount(); got != 0 {
				t.Fatalf("breaker failure count = %d, want 0 (4xx never trips the breaker)", got)
			}
			if env.Breaker.State() != providers.BreakerClosed {
				t.Fatalf("breaker state = %s, want CLOSED after a 429", env.Breaker.State())
			}
			rows := requireRows(t, env, "Initiate", 1)
			if rows[0].Outcome != providers.OutcomeFailure || rows[0].StatusCode != 429 {
				t.Fatalf("429 audit row = outcome %q status %d, want failure/429", rows[0].Outcome, rows[0].StatusCode)
			}
			// Rate limit ≠ outage: the next call still flows.
			env.Fake.SetStatusCode(0)
			mustInitiate(t, env, "tx-429")
			if got, want := env.Fake.RequestsTo(PathTransfers), 2; got != want {
				t.Fatalf("wire calls after the 429 = %d, want %d (provider still routable)", got, want)
			}
		},
	},
	{
		name: "failure-4xx-mapped-business-sentinels",
		doc:  "Wire rejections carrying the mapped error codes (unsupported_operation / unsupported_currency / not_found) surface as their distinct provider sentinels via errors.Is — and never trip the breaker (the provider is alive).",
		run: func(t *testing.T) {
			for _, c := range []struct {
				code     string
				status   int
				sentinel error
			}{
				{"unsupported_operation", 400, providers.ErrUnsupported},
				{"unsupported_currency", 400, providers.ErrUnsupportedCurrency},
				{"not_found", 404, providers.ErrNotFound},
			} {
				t.Run("code-"+c.code, func(t *testing.T) {
					env := newEnv(t, func(o *EnvOpts) {
						o.Server.StatusCode = c.status
						o.Server.ErrorCode = c.code
					})
					_, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
						TransactionKey: "tx-" + c.code,
						Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
						Rail:           "honeycoin",
						Destination:    providers.Destination{Type: "msisdn"},
					})
					if err == nil {
						t.Fatalf("wire rejection with code %q must error", c.code)
					}
					if !errors.Is(err, c.sentinel) {
						t.Fatalf("wire code %q must surface %v (matchable with errors.Is) — business errors must not be swallowed or mislabeled; got: %v", c.code, c.sentinel, err)
					}
					if got := env.Breaker.FailureCount(); got != 0 {
						t.Fatalf("breaker failure count = %d, want 0 (business rejection ≠ outage)", got)
					}
					rows := requireRows(t, env, "Initiate", 1)
					if rows[0].Outcome != providers.OutcomeFailure {
						t.Fatalf("audit outcome = %q, want failure", rows[0].Outcome)
					}
				})
			}
		},
	},
	{
		name: "failure-timeout-surfaces-deadline-error-and-is-audited",
		doc:  "A wire slower than the adapter's per-request timeout (600ms latency vs 150ms timeout) must return an error wrapping context.DeadlineExceeded, be audited as failure with real latency, and count against the breaker.",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) {
				o.AdapterTimeout = 150 * time.Millisecond
				o.Server.Latency = 600 * time.Millisecond
			})
			_, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-timeout",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if err == nil {
				t.Fatal("a wire slower than the timeout must fail — hanging providers can never hang a payout")
			}
			if !errors.Is(err, context.DeadlineExceeded) {
				t.Fatalf("timeout must wrap context.DeadlineExceeded so callers can classify it, got: %v", err)
			}
			if got := env.Breaker.FailureCount(); got != 1 {
				t.Fatalf("breaker failure count = %d, want 1 (timeouts count as failures)", got)
			}
			rows := requireRows(t, env, "Initiate", 1)
			if rows[0].Outcome != providers.OutcomeFailure {
				t.Fatalf("timeout audit outcome = %q, want failure", rows[0].Outcome)
			}
			if rows[0].Latency < 100*time.Millisecond {
				t.Fatalf("timeout audit latency = %s, want >= 100ms (the call really hung on the wire)", rows[0].Latency)
			}
		},
	},
	{
		name: "failure-malformed-response-body",
		doc:  "HTTP 200 with a non-JSON body (protocol violation on a healthy transport) must surface as a decode error, be audited as outcome=failure despite the 2xx, and NOT trip the breaker (the HTTP layer was healthy).",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) { o.Server.MalformedBody = true })
			_, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-garbage",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if err == nil {
				t.Fatal("a non-JSON 200 body must fail the call (the adapter cannot know money moved)")
			}
			if !strings.Contains(err.Error(), "decode response") {
				t.Fatalf("decode failures must be identifiable, got: %v", err)
			}
			rows := requireRows(t, env, "Initiate", 1)
			if rows[0].Outcome != providers.OutcomeFailure {
				t.Fatalf("malformed-body audit outcome = %q, want failure (2xx wire, unusable body)", rows[0].Outcome)
			}
			if rows[0].StatusCode != 200 {
				t.Fatalf("malformed-body audit status = %d, want 200 (the wire DID answer 200 — that is the forensic fact)", rows[0].StatusCode)
			}
			if got := env.Breaker.FailureCount(); got != 0 {
				t.Fatalf("breaker failure count = %d, want 0 (HTTP-level success; decode failure is not an outage signal)", got)
			}
		},
	},
	{
		name: "failure-empty-transfer-id-protocol-violation",
		doc:  "A 202 'success' WITHOUT a transfer id is a protocol violation: the adapter must fail closed (no ref returned, caller parks) — the wire exchange itself completed, so its audit row is success while the caller gets the error. (Note: the adapter's inline comment claims the audit row is failure; the suite pins the actual behavior — see the deviations section of the WP-4 report.)",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) { o.Server.EmptyTransferID = true })
			ref, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-empty",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if err == nil {
				t.Fatal("202 without an id must be rejected loudly (empty ref would silently corrupt the idempotency chain)")
			}
			if !strings.Contains(err.Error(), "empty transfer id") {
				t.Fatalf("error should name the protocol violation, got: %v", err)
			}
			if ref.Provider != "" || ref.Ref != "" {
				t.Fatalf("no ref may be returned on a malformed success, got %+v", ref)
			}
			// The wire exchange itself answered 202: the audit row records
			// the wire fact (success), the caller-visible failure is the
			// empty-ref rejection above.
			rows := requireRows(t, env, "Initiate", 1)
			if rows[0].Outcome != providers.OutcomeSuccess || rows[0].StatusCode != 202 {
				t.Fatalf("empty-id audit row = outcome %q status %d, want success/202 (the wire call itself completed; the protocol violation is the caller-visible error)", rows[0].Outcome, rows[0].StatusCode)
			}
		},
	},
	{
		name: "failure-signing-key-mismatch-rejected-as-401-not-an-outage",
		doc:  "Adapter signed with the WRONG key against a signature-enforcing provider: requests are 401-rejected and counted as bad signatures, the error surfaces, and the breaker does NOT trip (auth failure ≠ provider outage).",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) {
				o.AdapterSigningKey = []byte("wrong-adapter-side-key")
				o.Server.RejectUnsigned = true
			})
			_, err := env.Adapter.Initiate(context.Background(), providers.InitiateRequest{
				TransactionKey: "tx-wrongkey",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if err == nil {
				t.Fatal("a wrongly signed request must be rejected by the provider (401)")
			}
			if !strings.Contains(err.Error(), "HTTP 401") {
				t.Fatalf("auth rejection must quote HTTP 401, got: %v", err)
			}
			if got := env.Fake.BadSignatures(); got != 1 {
				t.Fatalf("bad signature counter = %d, want 1 (the fake is the independent signature oracle)", got)
			}
			if got := env.Breaker.FailureCount(); got != 0 {
				t.Fatalf("breaker failure count = %d, want 0 (401 is a 4xx business rejection — auth misconfig must not fail over traffic)", got)
			}
			rows := requireRows(t, env, "Initiate", 1)
			if rows[0].StatusCode != 401 || rows[0].Outcome != providers.OutcomeFailure {
				t.Fatalf("auth-rejection audit row = status %d outcome %q, want 401/failure", rows[0].StatusCode, rows[0].Outcome)
			}
		},
	},
	{
		name: "failure-caller-cancel-propagates-context-canceled",
		doc:  "A caller canceling mid-wire (400ms latency, canceled at 60ms) must surface context.Canceled and still be audited as failure — the audit trail records abandoned exchanges too.",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) { o.Server.Latency = 400 * time.Millisecond })
			ctx, cancel := context.WithCancel(context.Background())
			time.AfterFunc(60*time.Millisecond, cancel)
			defer cancel()
			_, err := env.Adapter.Initiate(ctx, providers.InitiateRequest{
				TransactionKey: "tx-cancel-ctx",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			})
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("caller cancellation must surface context.Canceled, got: %v", err)
			}
			rows := requireRows(t, env, "Initiate", 1)
			if rows[0].Outcome != providers.OutcomeFailure {
				t.Fatalf("canceled call audit outcome = %q, want failure", rows[0].Outcome)
			}
		},
	},

	// -------------------------------------------------------------------
	// Circuit breaker.
	// -------------------------------------------------------------------
	{
		name: "breaker-opens-after-threshold-and-stops-wire-traffic",
		doc:  "3 consecutive injected 500s (threshold=3) trip the breaker OPEN; the 4th call fails fast with provider.ErrProviderUnavailable (the router's failover signal) and the wire request count stays at 3 — calls stop reaching the server.",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) {
				o.Breaker = providers.BreakerConfig{FailureThreshold: 3, FailureWindow: time.Minute, OpenTimeout: 500 * time.Millisecond, ProbeLimit: 1}
				o.Server.StatusCode = 500
			})
			req := providers.InitiateRequest{
				TransactionKey: "tx-breaker",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			}
			for i := 0; i < 3; i++ {
				if _, err := env.Adapter.Initiate(context.Background(), req); err == nil {
					t.Fatalf("injected-500 initiate #%d must fail", i+1)
				}
			}
			if env.Breaker.State() != providers.BreakerOpen {
				t.Fatalf("breaker state after 3 failures = %s, want OPEN", env.Breaker.State())
			}
			if got := env.Fake.TotalRequests(); got != 3 {
				t.Fatalf("wire requests = %d, want 3 (three failures reached the server)", got)
			}
			_, err := env.Adapter.Initiate(context.Background(), req)
			if !errors.Is(err, providers.ErrProviderUnavailable) {
				t.Fatalf("call with the breaker open must fail with provider.ErrProviderUnavailable (failover signal), got: %v", err)
			}
			if got := env.Fake.TotalRequests(); got != 3 {
				t.Fatalf("wire requests after open-breaker call = %d, want 3 — the rejected call must NOT reach the provider", got)
			}
		},
	},
	{
		name: "breaker-open-call-is-audited-as-unavailable",
		doc:  "A call rejected by the open breaker still produces an audit row (repudiation control): outcome=unavailable, status 0, empty response, fast-fail latency — proving no wire traffic occurred.",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) {
				o.Breaker = providers.BreakerConfig{FailureThreshold: 3, FailureWindow: time.Minute, OpenTimeout: 500 * time.Millisecond, ProbeLimit: 1}
				o.Server.StatusCode = 500
			})
			req := providers.InitiateRequest{
				TransactionKey: "tx-audit-open",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			}
			for i := 0; i < 3; i++ {
				_, _ = env.Adapter.Initiate(context.Background(), req)
			}
			_, _ = env.Adapter.Initiate(context.Background(), req) // rejected by the breaker
			rows := requireRows(t, env, "Initiate", 4)
			last := rows[3]
			if last.Outcome != providers.OutcomeUnavailable {
				t.Fatalf("open-breaker audit outcome = %q, want %q", last.Outcome, providers.OutcomeUnavailable)
			}
			if last.StatusCode != 0 {
				t.Fatalf("open-breaker audit status = %d, want 0 (no wire exchange happened)", last.StatusCode)
			}
			if last.Response != "" {
				t.Fatalf("open-breaker audit response = %q, want empty (nothing came back from the wire)", last.Response)
			}
			if last.Latency > 100*time.Millisecond {
				t.Fatalf("open-breaker call latency = %s, want a fast fail (< 100ms — no wire round-trip)", last.Latency)
			}
		},
	},
	{
		name: "breaker-half-open-probe-recovers-to-closed",
		doc:  "After the open timeout (80ms) elapses, exactly ONE probe call is admitted; when it succeeds the breaker closes and the probe demonstrably reached the server (request count 3 → 4).",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) {
				o.Breaker = providers.BreakerConfig{FailureThreshold: 3, FailureWindow: time.Minute, OpenTimeout: 80 * time.Millisecond, ProbeLimit: 1}
				o.Server.StatusCode = 500
			})
			req := providers.InitiateRequest{
				TransactionKey: "tx-recover",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			}
			for i := 0; i < 3; i++ {
				_, _ = env.Adapter.Initiate(context.Background(), req)
			}
			if env.Breaker.State() != providers.BreakerOpen {
				t.Fatalf("breaker state = %s, want OPEN before recovery", env.Breaker.State())
			}
			time.Sleep(120 * time.Millisecond) // open timeout elapses
			env.Fake.SetStatusCode(0)          // provider heals
			ref := mustInitiate(t, env, "tx-recover")
			if ref.Ref == "" {
				t.Fatal("recovery probe must succeed")
			}
			if env.Breaker.State() != providers.BreakerClosed {
				t.Fatalf("breaker state after a successful probe = %s, want CLOSED", env.Breaker.State())
			}
			if got := env.Fake.TotalRequests(); got != 4 {
				t.Fatalf("wire requests = %d, want 4 (3 failures + 1 admitted probe — exactly one probe goes out)", got)
			}
			if got := env.Breaker.FailureCount(); got != 0 {
				t.Fatalf("failure count after recovery = %d, want 0 (success forgets failures)", got)
			}
		},
	},
	{
		name: "breaker-half-open-probe-failure-reopens",
		doc:  "If the recovery probe fails (still 500), the breaker re-opens immediately and subsequent calls are again rejected without any wire traffic (3 failures + 1 probe = 4 requests, then silence).",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) {
				o.Breaker = providers.BreakerConfig{FailureThreshold: 3, FailureWindow: time.Minute, OpenTimeout: 80 * time.Millisecond, ProbeLimit: 1}
				o.Server.StatusCode = 500
			})
			req := providers.InitiateRequest{
				TransactionKey: "tx-reopen",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			}
			for i := 0; i < 3; i++ {
				_, _ = env.Adapter.Initiate(context.Background(), req)
			}
			time.Sleep(120 * time.Millisecond)
			if _, err := env.Adapter.Initiate(context.Background(), req); err == nil {
				t.Fatal("probe against a still-failing provider must fail")
			}
			if env.Breaker.State() != providers.BreakerOpen {
				t.Fatalf("breaker state after failed probe = %s, want OPEN (re-opened for a full timeout)", env.Breaker.State())
			}
			_, err := env.Adapter.Initiate(context.Background(), req)
			if !errors.Is(err, providers.ErrProviderUnavailable) {
				t.Fatalf("post-reopen call must be rejected with ErrProviderUnavailable, got: %v", err)
			}
			if got := env.Fake.TotalRequests(); got != 4 {
				t.Fatalf("wire requests = %d, want 4 (3 failures + 1 failed probe; the rejected call added none)", got)
			}
		},
	},
	{
		name: "breaker-consecutive-4xx-rejections-never-open-the-breaker",
		doc:  "Ten consecutive 4xx business rejections (400 unsupported_operation) leave the breaker CLOSED and traffic flowing — only transport errors, timeouts and 5xx count (the provider is demonstrably alive).",
		run: func(t *testing.T) {
			env := newEnv(t, func(o *EnvOpts) {
				o.Breaker = providers.BreakerConfig{FailureThreshold: 3, FailureWindow: time.Minute, OpenTimeout: 80 * time.Millisecond, ProbeLimit: 1}
				o.Server.StatusCode = 400
				o.Server.ErrorCode = "unsupported_operation"
			})
			req := providers.InitiateRequest{
				TransactionKey: "tx-4xx-storm",
				Amount:         providers.Money{AmountMinor: CannedAmountMinor, Currency: CannedCurrency, Exponent: 2},
				Rail:           "honeycoin",
				Destination:    providers.Destination{Type: "msisdn"},
			}
			for i := 0; i < 10; i++ {
				if _, err := env.Adapter.Initiate(context.Background(), req); !errors.Is(err, providers.ErrUnsupported) {
					t.Fatalf("4xx storm call #%d must surface ErrUnsupported, got: %v", i+1, err)
				}
			}
			if env.Breaker.State() != providers.BreakerClosed {
				t.Fatalf("breaker state after 10 business rejections = %s, want CLOSED", env.Breaker.State())
			}
			if got := env.Fake.TotalRequests(); got != 10 {
				t.Fatalf("wire requests = %d, want 10 (all rejections reached the provider — no suppression)", got)
			}
		},
	},

	// -------------------------------------------------------------------
	// NotFound / edge.
	// -------------------------------------------------------------------
	{
		name: "poll-unknown-ref-surfaces-not-found-sentinel",
		doc:  "Polling a ref the provider does not know returns 404 not_found on the wire; the adapter must surface provider.ErrNotFound (matchable with errors.Is) with an audited failure row — and the 404 must not trip the breaker.",
		run: func(t *testing.T) {
			env := newEnv(t)
			st, err := env.Adapter.Poll(context.Background(), providers.ProviderRef{Provider: "honeycoin", Ref: "hct_nope"})
			if err == nil {
				t.Fatal("polling an unknown ref must error (never guess a status)")
			}
			if !errors.Is(err, providers.ErrNotFound) {
				t.Fatalf("unknown ref must surface provider.ErrNotFound, got: %v", err)
			}
			if st != "" {
				t.Fatalf("no status may be returned alongside ErrNotFound, got %q", st)
			}
			if got := env.Fake.TotalRequests(); got != 1 {
				t.Fatalf("wire requests = %d, want 1 (the lookup did go out)", got)
			}
			rows := requireRows(t, env, "Poll", 1)
			if rows[0].Outcome != providers.OutcomeFailure || rows[0].StatusCode != 404 {
				t.Fatalf("unknown-ref audit row = status %d outcome %q, want 404/failure", rows[0].StatusCode, rows[0].Outcome)
			}
			if got := env.Breaker.FailureCount(); got != 0 {
				t.Fatalf("breaker failure count = %d, want 0 (404 is a 4xx)", got)
			}
		},
	},
}

func TestConformanceScenarios(t *testing.T) {
	runScenarios(t, conformanceScenarios)
}
