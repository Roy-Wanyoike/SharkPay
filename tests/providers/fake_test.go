package conformance

import (
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers"
)

// harnessScenarios pin the HARNESS itself: its knobs, counters, callback
// factory, and — most importantly — that the fake's independently
// implemented wire scheme agrees with the SUT's exported contract surface.
// If one of these fails, every conformance result is suspect.
var harnessScenarios = []scenario{
	{
		name: "harness-contract-constants-pin-sut-exports",
		doc:  "The fake's local header constants and signing scheme must agree with the SUT's exported constants and SignHoneyCoinRequest — otherwise the fake would be validating a contract the adapter no longer speaks.",
		run: func(t *testing.T) {
			if got, want := providers.IdempotencyHeader, HeaderIdempotencyKey; got != want {
				t.Fatalf("wire contract drift: providers.IdempotencyHeader = %q, want %q (the header the fake verifies)", got, want)
			}
			if got, want := providers.CallbackTimestampHeader, HeaderCallbackTimestamp; got != want {
				t.Fatalf("wire contract drift: providers.CallbackTimestampHeader = %q, want %q", got, want)
			}
			if got, want := providers.CallbackSignatureHeader, HeaderCallbackSignature; got != want {
				t.Fatalf("wire contract drift: providers.CallbackSignatureHeader = %q, want %q", got, want)
			}
			// Independent HMAC vector vs the SUT's exported request signer.
			key := []byte("pin-vector-key")
			ts, method, path := "1770000000", "POST", "/v1/transfers"
			body := []byte(`{"amount_minor":150000}`)
			local := signRequestMessage(key, ts, method, path, body)
			sut := providers.SignHoneyCoinRequest(key, ts, method, path, body)
			if local != sut {
				t.Fatalf("request signing drift: local HMAC = %s, SUT SignHoneyCoinRequest = %s (ts %q, %s %s, body %s)", local, sut, ts, method, path, body)
			}
			if !verifyRequestSignature(key, ts, method, path, body, sut) {
				t.Fatal("the fake's verifier must accept a signature produced by the SUT's exported signer")
			}
			sig, err := hex.DecodeString(local)
			if err != nil || len(sig) != 32 {
				t.Fatalf("request signature must be hex sha256 (64 chars, 32 bytes): len=%d err=%v", len(sig), err)
			}
			// Independent callback scheme vector.
			cbBody := []byte(`{"ref":"hct_stub_000001","status":"CONFIRMED"}`)
			cbLocal := signCallbackMessage([]byte("s"), "1770000001", cbBody)
			mac := signCallbackMessage([]byte("s"), "1770000001", cbBody)
			if cbLocal != mac || len(cbLocal) != 64 {
				t.Fatalf("callback signature must be a deterministic 64-char hex HMAC, got %q", cbLocal)
			}
		},
	},
	{
		name: "harness-latency-knob-delays-response",
		doc:  "ServerConfig.Latency must actually delay the wire response so timeout scenarios inject real deadline pressure.",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{Latency: 120 * time.Millisecond})
			t.Cleanup(fake.Close)
			start := time.Now()
			resp, err := fake.DoSigned(http.MethodGet, PathTransfers+"/hct_none", nil, "")
			if err != nil {
				t.Fatalf("signed request failed: %v", err)
			}
			elapsed := time.Since(start)
			if elapsed < 110*time.Millisecond {
				t.Fatalf("latency knob not applied: response after %s, expected >= 110ms", elapsed)
			}
			if resp.StatusCode != http.StatusNotFound {
				t.Fatalf("expected 404 for unknown transfer even with latency, got %d", resp.StatusCode)
			}
		},
	},
	{
		name: "harness-status-code-knob-injects-error",
		doc:  "ServerConfig.StatusCode must make every endpoint answer with the injected status and an error envelope carrying the injected code.",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{StatusCode: 503})
			t.Cleanup(fake.Close)
			resp, err := fake.DoSigned(http.MethodPost, PathTransfers, []byte(`{"amount_minor":150000,"currency":"KES","exponent":2}`), "tx")
			if err != nil {
				t.Fatalf("signed request failed: %v", err)
			}
			if resp.StatusCode != 503 {
				t.Fatalf("status knob: got HTTP %d, want 503", resp.StatusCode)
			}
			if !strings.Contains(string(resp.Body), "injected_failure") {
				t.Fatalf("status knob: error code not in body: %s", resp.Body)
			}
		},
	},
	{
		name: "harness-malformed-body-knob-returns-garbage",
		doc:  "ServerConfig.MalformedBody must return HTTP 200 with a non-JSON body (transport-healthy wire, protocol violation).",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{MalformedBody: true})
			t.Cleanup(fake.Close)
			resp, err := fake.DoSigned(http.MethodPost, PathTransfers, []byte(`{"amount_minor":1,"currency":"KES","exponent":2}`), "tx")
			if err != nil {
				t.Fatalf("signed request failed: %v", err)
			}
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("malformed knob: got HTTP %d, want 200", resp.StatusCode)
			}
			if json.Valid(resp.Body) {
				t.Fatalf("malformed knob: body must NOT be valid JSON, got %s", resp.Body)
			}
		},
	},
	{
		name: "harness-reject-unsigned-knob-counts-bad-signatures",
		doc:  "RejectUnsigned must 401 unsigned/incorrectly signed requests while correctly signed requests still pass, and BadSignatures() counts only the failures.",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{RejectUnsigned: true})
			t.Cleanup(fake.Close)
			// Unsigned request → 401, counted.
			r, err := http.Post(fake.URL()+PathTransfers, "application/json", strings.NewReader(`{"amount_minor":1,"currency":"KES","exponent":2}`))
			if err != nil {
				t.Fatalf("unsigned request failed: %v", err)
			}
			_, _ = io.ReadAll(r.Body)
			_ = r.Body.Close()
			if r.StatusCode != http.StatusUnauthorized {
				t.Fatalf("RejectUnsigned: unsigned request got HTTP %d, want 401", r.StatusCode)
			}
			if got := fake.BadSignatures(); got != 1 {
				t.Fatalf("BadSignatures() = %d, want 1 (the unsigned request)", got)
			}
			// Correctly signed request → accepted.
			resp, err := fake.DoSigned(http.MethodPost, PathTransfers, []byte(`{"amount_minor":1,"currency":"KES","exponent":2}`), "tx")
			if err != nil {
				t.Fatalf("signed request failed: %v", err)
			}
			if resp.StatusCode != http.StatusAccepted {
				t.Fatalf("RejectUnsigned: signed request got HTTP %d, want 202", resp.StatusCode)
			}
			if got := fake.BadSignatures(); got != 1 {
				t.Fatalf("BadSignatures() = %d after a valid request, want 1 (valid requests are not counted)", got)
			}
		},
	},
	{
		name: "harness-unknown-ref-returns-404-not-found",
		doc:  "GET/cancel/reverse on an unknown ref must answer 404 with error code not_found — the wire shape the adapter maps to provider.ErrNotFound.",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{})
			t.Cleanup(fake.Close)
			for _, c := range []struct{ method, path string }{
				{http.MethodGet, PathTransfers + "/hct_unknown"},
				{http.MethodPost, PathTransfers + "/hct_unknown/cancel"},
				{http.MethodPost, PathTransfers + "/hct_unknown/reverse"},
			} {
				resp, err := fake.DoSigned(c.method, c.path, nil, "")
				if err != nil {
					t.Fatalf("%s %s: signed request failed: %v", c.method, c.path, err)
				}
				if resp.StatusCode != http.StatusNotFound {
					t.Fatalf("%s %s: got HTTP %d, want 404", c.method, c.path, resp.StatusCode)
				}
				if !strings.Contains(string(resp.Body), "not_found") {
					t.Fatalf("%s %s: 404 body must carry code not_found, got %s", c.method, c.path, resp.Body)
				}
			}
		},
	},
	{
		name: "harness-quote-bad-request-body-returns-400",
		doc:  "A non-JSON quote request must be rejected with 400 bad_request (wire-level validation).",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{})
			t.Cleanup(fake.Close)
			resp, err := fake.DoSigned(http.MethodPost, PathQuotes, []byte("not json"), "")
			if err != nil {
				t.Fatalf("signed request failed: %v", err)
			}
			if resp.StatusCode != http.StatusBadRequest {
				t.Fatalf("quote bad body: got HTTP %d, want 400", resp.StatusCode)
			}
			if !strings.Contains(string(resp.Body), "bad_request") {
				t.Fatalf("quote bad body: code not_found expected bad_request, got %s", resp.Body)
			}
		},
	},
	{
		name: "harness-recon-window-filters-and-zero-time-lines-escape",
		doc:  "Reconciliation lines inside [From, To) are returned, lines outside are excluded, and lines with a zero OccurredAt are always reported (window-unknown).",
		run: func(t *testing.T) {
			now := time.Now()
			fake := NewFakeHoneyCoin(ServerConfig{})
			t.Cleanup(fake.Close)
			fake.SetReconLines([]ReconLine{
				{ID: "in-window", Status: "CONFIRMED", AmountMinor: 100, FeeMinor: 1, Currency: "KES", Exponent: 2, OccurredAt: now},
				{ID: "out-window", Status: "FAILED", AmountMinor: 200, FeeMinor: 2, Currency: "KES", Exponent: 2, OccurredAt: now.Add(-2 * time.Hour)},
				{ID: "no-time", Status: "REVERSED", AmountMinor: 300, FeeMinor: 3, Currency: "KES", Exponent: 2},
			})
			resp, err := fake.DoSigned(http.MethodPost, PathReconciliation, []byte(
				`{"from":"`+now.Add(-time.Hour).UTC().Format(time.RFC3339)+`","to":"`+now.Add(time.Hour).UTC().Format(time.RFC3339)+`"}`), "")
			if err != nil {
				t.Fatalf("signed request failed: %v", err)
			}
			body := string(resp.Body)
			if !strings.Contains(body, `"in-window"`) || !strings.Contains(body, `"no-time"`) {
				t.Fatalf("recon window: in-window and zero-time lines missing: %s", body)
			}
			if strings.Contains(body, `"out-window"`) {
				t.Fatalf("recon window: out-of-window line must be excluded: %s", body)
			}
		},
	},
	{
		name: "harness-callback-builder-produces-forgery-vectors",
		doc:  "MakeCallback must produce a correctly signed 64-hex-char envelope by default, and the tamper/drop options must actually alter the headers.",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{})
			t.Cleanup(fake.Close)
			cb := fake.MakeCallback("hct_stub_000001", "CONFIRMED", 150000, "KES", 2, CallbackOptions{})
			if cb.Provider != "honeycoin" {
				t.Fatalf("default callback provider = %q, want honeycoin", cb.Provider)
			}
			sig, ok := cb.Headers[HeaderCallbackSignature]
			if !ok || len(sig) != 64 {
				t.Fatalf("default callback signature missing or malformed: %q", sig)
			}
			if _, err := hex.DecodeString(sig); err != nil {
				t.Fatalf("default callback signature must be hex: %v", err)
			}
			ts, ok := cb.Headers[HeaderCallbackTimestamp]
			if !ok || ts == "" {
				t.Fatal("default callback timestamp header missing")
			}
			// The signature must actually verify against the local scheme.
			if signCallbackMessage([]byte(DefaultCallbackSecret), ts, cb.Body) != sig {
				t.Fatal("default callback signature does not match the local independent scheme")
			}
			tampered := fake.MakeCallback("hct_stub_000001", "CONFIRMED", 150000, "KES", 2, CallbackOptions{TamperSignature: true})
			if tampered.Headers[HeaderCallbackSignature] == sig {
				t.Fatal("TamperSignature must change the signature")
			}
			dropped := fake.MakeCallback("hct_stub_000001", "CONFIRMED", 150000, "KES", 2, CallbackOptions{DropSignature: true, DropTimestamp: true})
			if _, ok := dropped.Headers[HeaderCallbackSignature]; ok {
				t.Fatal("DropSignature must omit the signature header")
			}
			if _, ok := dropped.Headers[HeaderCallbackTimestamp]; ok {
				t.Fatal("DropTimestamp must omit the timestamp header")
			}
			// The mapping URL pattern must match the fake's transfer route.
			pattern := regexp.MustCompile(`/v1/transfers/[^/]+`)
			if !pattern.MatchString(PathTransfers + "/hct_stub_000001") {
				t.Fatal("status mapping pattern must match the fake's GET transfer path")
			}
		},
	},
}

func TestHarnessScenarios(t *testing.T) {
	runScenarios(t, harnessScenarios)
}
