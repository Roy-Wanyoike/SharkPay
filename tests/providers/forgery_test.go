package conformance

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers"
)

// forgeryScenarios is the forgery/anti-replay suite: every way an attacker
// can forge, replay or misroute a provider callback must be rejected with
// the DISTINCT sentinel error (SECURITY §4/§6) and audited as a security
// event. The callback body is never trusted until the envelope verifies.
var forgeryScenarios = []scenario{
	{
		name: "forged-tampered-signature-rejected-as-bad-signature",
		doc:  "A callback whose signature hex was flipped after signing (tamper on the wire) must fail with provider.ErrBadSignature; the body status is never applied.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-forgery-tamper")
			// Control: the untampered envelope verifies.
			good := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{})
			if st, err := env.Adapter.HandleCallback(context.Background(), good); err != nil || st != providers.StatusSucceeded {
				t.Fatalf("control envelope must verify: status=%s err=%v", st, err)
			}
			forged := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{TamperSignature: true})
			st, err := env.Adapter.HandleCallback(context.Background(), forged)
			if !errors.Is(err, providers.ErrBadSignature) {
				t.Fatalf("tampered signature must surface provider.ErrBadSignature (SECURITY §6 alert signal), got: %v", err)
			}
			if st != "" {
				t.Fatalf("a rejected callback must not apply its status, got %q", st)
			}
			if rows := auditRows(t, env, "HandleCallback"); len(rows) != 2 || rows[1].Outcome != providers.OutcomeFailure {
				t.Fatalf("forged callback must be audited as a security event (outcome failure): %+v", rows)
			}
		},
	},
	{
		name: "forged-wrong-secret-rejected-as-bad-signature",
		doc:  "A callback signed with an attacker's secret (key substitution) fails HMAC verification with provider.ErrBadSignature.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-forgery-secret")
			forged := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2,
				CallbackOptions{Secret: []byte("attacker-owned-secret")})
			st, err := env.Adapter.HandleCallback(context.Background(), forged)
			if !errors.Is(err, providers.ErrBadSignature) {
				t.Fatalf("wrong-secret signature must surface provider.ErrBadSignature, got: %v", err)
			}
			if st != "" {
				t.Fatalf("a rejected callback must not apply its status, got %q", st)
			}
		},
	},
	{
		name: "forged-missing-signature-header-rejected-as-malformed",
		doc:  "A callback with the timestamp header but NO signature header is malformed (missing verification material) — provider.ErrMalformed.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-forgery-nosig")
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{DropSignature: true})
			_, err := env.Adapter.HandleCallback(context.Background(), cb)
			if !errors.Is(err, providers.ErrMalformed) {
				t.Fatalf("missing signature header must surface provider.ErrMalformed, got: %v", err)
			}
		},
	},
	{
		name: "forged-missing-timestamp-header-rejected-as-malformed",
		doc:  "A callback with a signature header but NO timestamp is malformed — freshness cannot be established — provider.ErrMalformed.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-forgery-nots")
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{DropTimestamp: true})
			_, err := env.Adapter.HandleCallback(context.Background(), cb)
			if !errors.Is(err, providers.ErrMalformed) {
				t.Fatalf("missing timestamp header must surface provider.ErrMalformed, got: %v", err)
			}
		},
	},
	{
		name: "forged-undecodable-hex-signature-rejected-as-bad-signature",
		doc:  "A signature header that is not valid hex fails signature decoding — provider.ErrBadSignature (not a crash, not a 500).",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-forgery-hex")
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2,
				CallbackOptions{Signature: "zz-not-valid-hex"})
			_, err := env.Adapter.HandleCallback(context.Background(), cb)
			if !errors.Is(err, providers.ErrBadSignature) {
				t.Fatalf("non-hex signature must surface provider.ErrBadSignature, got: %v", err)
			}
		},
	},
	{
		name: "forged-non-json-body-rejected-as-malformed",
		doc:  "A correctly-signed envelope whose body is not JSON (no ref field ⇒ no replay nonce) is rejected with provider.ErrMalformed — the signature authenticates bytes, not semantics.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-forgery-body")
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2,
				CallbackOptions{Body: []byte("this is not json")})
			_, err := env.Adapter.HandleCallback(context.Background(), cb)
			if !errors.Is(err, providers.ErrMalformed) {
				t.Fatalf("signed-but-unparseable body must surface provider.ErrMalformed (no ref to scope the replay nonce), got: %v", err)
			}
		},
	},
	{
		name: "forged-stale-past-timestamp-rejected-as-stale",
		doc:  "A callback timestamped 6 minutes in the past (outside the ±5 min window) is rejected with provider.ErrStale even though its signature is valid — freshness is mandatory.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-forgery-stale")
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2,
				CallbackOptions{Timestamp: time.Now().Add(-6 * time.Minute)})
			st, err := env.Adapter.HandleCallback(context.Background(), cb)
			if !errors.Is(err, providers.ErrStale) {
				t.Fatalf("stale timestamp must surface provider.ErrStale, got: %v", err)
			}
			if st != "" {
				t.Fatalf("a stale callback must not apply its status, got %q", st)
			}
		},
	},
	{
		name: "forged-future-timestamp-rejected-as-stale",
		doc:  "A callback timestamped 6 minutes in the FUTURE is equally rejected with provider.ErrStale (symmetric ±window — blocks pre-computed/future-dated replay material).",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-forgery-future")
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2,
				CallbackOptions{Timestamp: time.Now().Add(6 * time.Minute)})
			_, err := env.Adapter.HandleCallback(context.Background(), cb)
			if !errors.Is(err, providers.ErrStale) {
				t.Fatalf("future timestamp must surface provider.ErrStale (±window is symmetric), got: %v", err)
			}
		},
	},
	{
		name: "replayed-callback-rejected-as-replay",
		doc:  "The same verified envelope delivered twice (nonce reuse): the first delivery applies; the second is rejected with provider.ErrReplay within the replay TTL.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-replay")
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{})
			st, err := env.Adapter.HandleCallback(context.Background(), cb)
			if err != nil || st != providers.StatusSucceeded {
				t.Fatalf("first delivery must apply: status=%s err=%v", st, err)
			}
			st, err = env.Adapter.HandleCallback(context.Background(), cb)
			if !errors.Is(err, providers.ErrReplay) {
				t.Fatalf("replayed envelope must surface provider.ErrReplay (nonce provider|ref|ts already seen), got: %v", err)
			}
			if st != "" {
				t.Fatalf("a replayed callback must not re-apply its status, got %q", st)
			}
			rows := auditRows(t, env, "HandleCallback")
			if len(rows) != 2 || rows[1].Outcome != providers.OutcomeFailure {
				t.Fatalf("replay attempt must be audited as failure (security event): %+v", rows)
			}
		},
	},
	{
		name: "replay-nonce-scoped-to-ref-same-timestamp-is-allowed",
		doc:  "Two DIFFERENT transfers notified at the same timestamp are both accepted — the nonce is provider|ref|ts, so legitimate concurrent notifications are not false-flagged as replays.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref1 := mustInitiate(t, env, "tx-nonce-1")
			ref2 := mustInitiate(t, env, "tx-nonce-2")
			ts := time.Now()
			cb1 := env.Fake.MakeCallback(ref1.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{Timestamp: ts})
			cb2 := env.Fake.MakeCallback(ref2.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{Timestamp: ts})
			for i, cb := range []providers.Callback{cb1, cb2} {
				st, err := env.Adapter.HandleCallback(context.Background(), cb)
				if err != nil || st != providers.StatusSucceeded {
					t.Fatalf("legitimate same-timestamp callback #%d must verify (nonce scoped to ref): status=%s err=%v", i+1, st, err)
				}
			}
		},
	},
	{
		name: "misrouted-provider-callback-rejected",
		doc:  "A callback envelope claiming provider \"mpesa\" delivered to the HoneyCoin adapter is rejected as misrouted BEFORE verification — and generates no wire traffic.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-misroute")
			cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2,
				CallbackOptions{ProviderName: "mpesa"})
			_, err := env.Adapter.HandleCallback(context.Background(), cb)
			if err == nil {
				t.Fatal("a callback for another provider must be rejected by this adapter")
			}
			if !strings.Contains(err.Error(), "wrong adapter") {
				t.Fatalf("misrouted callback error must name the routing problem (wrong adapter), got: %v", err)
			}
			if got := env.Fake.TotalRequests(); got != 1 {
				t.Fatalf("wire requests = %d, want 1 (only the initiate; callbacks are inbound and must not trigger wire traffic)", got)
			}
			rows := auditRows(t, env, "HandleCallback")
			if len(rows) != 1 || rows[0].Outcome != providers.OutcomeFailure {
				t.Fatalf("misrouted callback must be audited as failure: %+v", rows)
			}
		},
	},
	{
		name: "forgery-sentinels-are-distinct",
		doc:  "Cross-check: each forged-callback error matches EXACTLY its own sentinel (ErrBadSignature / ErrStale / ErrReplay / ErrMalformed) and none of the others — ingress can alert precisely instead of guessing.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-matrix")

			mk := func(opts CallbackOptions) error {
				cb := env.Fake.MakeCallback(ref.Ref, "CONFIRMED", CannedAmountMinor, CannedCurrency, 2, opts)
				_, err := env.Adapter.HandleCallback(context.Background(), cb)
				if err == nil {
					t.Fatalf("forgery vector %+v must be rejected", opts)
				}
				return err
			}
			cases := []struct {
				label    string
				err      error
				sentinel error
			}{
				{"bad-signature", mk(CallbackOptions{TamperSignature: true}), providers.ErrBadSignature},
				{"stale", mk(CallbackOptions{Timestamp: time.Now().Add(-6 * time.Minute)}), providers.ErrStale},
				{"malformed", mk(CallbackOptions{DropSignature: true}), providers.ErrMalformed},
			}
			// Replay needs one accepted delivery first (same explicit timestamp
			// ⇒ same nonce, no wall-clock race).
			ts := time.Now()
			good := env.Fake.MakeCallback(ref.Ref, "PROCESSING", CannedAmountMinor, CannedCurrency, 2, CallbackOptions{Timestamp: ts})
			if _, err := env.Adapter.HandleCallback(context.Background(), good); err != nil {
				t.Fatalf("control delivery must verify: %v", err)
			}
			cases = append(cases, struct {
				label    string
				err      error
				sentinel error
			}{"replay", mk(CallbackOptions{Timestamp: ts}), providers.ErrReplay})

			all := []error{providers.ErrBadSignature, providers.ErrStale, providers.ErrReplay, providers.ErrMalformed}
			for _, c := range cases {
				if !errors.Is(c.err, c.sentinel) {
					t.Fatalf("%s error must match %v, got: %v", c.label, c.sentinel, c.err)
				}
				for _, other := range all {
					if other != c.sentinel && errors.Is(c.err, other) {
						t.Fatalf("%s error must match ONLY %v, but also matched %v: %v", c.label, c.sentinel, other, c.err)
					}
				}
			}
		},
	},
}

func TestForgeryScenarios(t *testing.T) {
	runScenarios(t, forgeryScenarios)
}
