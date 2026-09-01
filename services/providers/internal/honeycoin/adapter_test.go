package honeycoin

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
)

func TestMapStatusTable(t *testing.T) {
	cases := []struct {
		wire string
		want provider.TransferStatus
	}{
		{"PENDING", provider.StatusPending},
		{"PROCESSING", provider.StatusProcessing},
		{"CONFIRMED", provider.StatusSucceeded},
		{"FAILED", provider.StatusFailed},
		{"REVERSED", provider.StatusReturned},
		{"RETURNED", provider.StatusReturned},
		{"pending", provider.StatusPending},       // case normalization
		{" CONFIRMED ", provider.StatusSucceeded}, // whitespace normalization
		{"", provider.StatusUnknown},              // empty → ambiguous
		{"SOMETHING_ODD", provider.StatusUnknown},
		{"CANCELLED", provider.StatusUnknown}, // not in the launch status set — see adapter doc
	}
	for _, c := range cases {
		if got := MapStatus(c.wire); got != c.want {
			t.Errorf("MapStatus(%q) = %s, want %s", c.wire, got, c.want)
		}
	}
}

func TestSignRequestScheme(t *testing.T) {
	key := []byte("k")
	sig := SignRequest(key, "1770000000", "POST", "/v1/transfers", []byte(`{"a":1}`))

	// deterministic
	if sig != SignRequest(key, "1770000000", "POST", "/v1/transfers", []byte(`{"a":1}`)) {
		t.Fatal("signature must be deterministic")
	}
	// different body → different signature
	if sig == SignRequest(key, "1770000000", "POST", "/v1/transfers", []byte(`{"a":2}`)) {
		t.Fatal("signature must cover the body")
	}
	// different path → different signature
	if sig == SignRequest(key, "1770000000", "POST", "/v1/quotes", []byte(`{"a":1}`)) {
		t.Fatal("signature must cover the path")
	}
	// verify helper agrees
	if !VerifyRequestMessage(key, "1770000000", "POST", "/v1/transfers", []byte(`{"a":1}`), sig) {
		t.Fatal("VerifyRequestMessage must accept a correct signature")
	}
	if VerifyRequestMessage(key, "1770000000", "POST", "/v1/transfers", []byte(`{"a":2}`), sig) {
		t.Fatal("VerifyRequestMessage must reject a tampered body")
	}
	// hex-encoded HMAC-SHA256 (64 chars)
	if len(sig) != 64 {
		t.Fatalf("signature length = %d, want 64 (hex sha256)", len(sig))
	}
	if _, err := hex.DecodeString(sig); err != nil {
		t.Fatalf("signature must be hex: %v", err)
	}
	// explicit reference vector: independent HMAC computation
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte("1770000000\nPOST\n/v1/transfers\n{\"a\":1}"))
	if sig != hex.EncodeToString(mac.Sum(nil)) {
		t.Fatal("signature must equal HMAC-SHA256(key, ts \"\\n\" method \"\\n\" path \"\\n\" body)")
	}
}

func TestRedact(t *testing.T) {
	body := []byte(`{"signature":"deadbeef","nested":{"token":"topsecret","ok":1},"list":[{"api_key":"zzz"}]}`)
	out := redact(body)
	if strings.Contains(out, "deadbeef") || strings.Contains(out, "topsecret") || strings.Contains(out, "zzz") {
		t.Fatalf("redaction leaked a secret: %s", out)
	}
	if !strings.Contains(out, "[REDACTED]") {
		t.Fatalf("redaction marker missing: %s", out)
	}
	if !strings.Contains(out, "\"ok\":1") {
		t.Fatalf("non-secret fields must survive: %s", out)
	}
	if redact(nil) != "" || redact([]byte{}) != "" {
		t.Fatal("empty bodies redact to an empty string")
	}
	if redact([]byte("not json")) != "<unparseable body>" {
		t.Fatal("non-JSON bodies must be flagged, not stored raw")
	}
}

func TestNewRequiresBaseURLAndKey(t *testing.T) {
	t.Setenv("HONEYCOIN_BASE_URL", "")
	t.Setenv("HONEYCOIN_SIGNING_KEY", "")
	if _, err := New(Config{}); err == nil {
		t.Fatal("New without base URL or key must fail")
	}
	t.Setenv("HONEYCOIN_BASE_URL", "http://localhost:9")
	if _, err := New(Config{}); err == nil {
		t.Fatal("New without a signing key must fail")
	}
	if _, err := New(Config{BaseURL: "http://localhost:9", SigningKey: []byte("k")}); err != nil {
		t.Fatalf("explicit config should build: %v", err)
	}
}

func TestResolveSigningKeyVaultRef(t *testing.T) {
	t.Setenv("HONEYCOIN_SIGNING_KEY", "vault:honeycoin/signing")
	if _, err := ResolveSigningKey(nil, "HONEYCOIN_SIGNING_KEY"); err == nil {
		t.Fatal("vault refs must be rejected until the Secrets Manager resolver lands")
	}
	t.Setenv("HONEYCOIN_SIGNING_KEY", "raw-key")
	k, err := ResolveSigningKey(nil, "HONEYCOIN_SIGNING_KEY")
	if err != nil || string(k) != "raw-key" {
		t.Fatalf("raw env key should resolve: %v %q", err, k)
	}
	if k, _ := ResolveSigningKey([]byte("explicit"), "HONEYCOIN_SIGNING_KEY"); string(k) != "explicit" {
		t.Fatal("explicit key must win over env")
	}
}

func TestCheckRefValidationStaysLocal(t *testing.T) {
	t.Setenv("HONEYCOIN_BASE_URL", "http://localhost:9")
	t.Setenv("HONEYCOIN_SIGNING_KEY", "k")
	a, err := New(Config{})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if err := a.checkRef(provider.ProviderRef{Provider: ProviderName, Ref: ""}); err == nil {
		t.Fatal("empty ref must be rejected")
	}
	if err := a.checkRef(provider.ProviderRef{Provider: "mpesa", Ref: "x"}); err == nil {
		t.Fatal("foreign provider ref must be rejected")
	}
	if err := a.checkRef(provider.ProviderRef{Provider: ProviderName, Ref: "hct/with/slash"}); err == nil {
		t.Fatal("refs outside the URL-safe signing charset must be rejected")
	}
	if err := a.checkRef(provider.ProviderRef{Ref: "hct_OK-123.x"}); err != nil {
		t.Fatalf("URL-safe ref must pass (empty provider is lenient): %v", err)
	}
}

func TestReconcileReportRejectsInvalidWindow(t *testing.T) {
	t.Setenv("HONEYCOIN_BASE_URL", "http://localhost:9")
	t.Setenv("HONEYCOIN_SIGNING_KEY", "k")
	a, err := New(Config{})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	from := time.Date(2026, 3, 14, 0, 0, 0, 0, time.UTC)
	if _, err := a.ReconcileReport(context.Background(), provider.Window{From: from, To: from}); !isWindowErr(err) {
		t.Fatalf("empty window should be rejected locally: %v", err)
	}
	if _, err := a.ReconcileReport(context.Background(), provider.Window{From: from.Add(time.Hour), To: from}); !isWindowErr(err) {
		t.Fatalf("inverted window should be rejected locally: %v", err)
	}
}

func isWindowErr(err error) bool {
	return err != nil && strings.Contains(err.Error(), "window")
}

func TestCallbackWrongProviderRouting(t *testing.T) {
	t.Setenv("HONEYCOIN_BASE_URL", "http://localhost:9")
	t.Setenv("HONEYCOIN_SIGNING_KEY", "k")
	a, err := New(Config{})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	_, err = a.HandleCallback(context.Background(), provider.Callback{
		Provider: "someone-else",
		Headers:  map[string]string{},
		Body:     []byte(`{"ref":"x"}`),
	})
	if err == nil || !strings.Contains(err.Error(), "wrong adapter") {
		t.Fatalf("misrouted callback must be rejected: %v", err)
	}
}

// wireError decoding sanity (used by mapWireError).
func TestWireErrorParsing(t *testing.T) {
	var we wireError
	if err := json.Unmarshal([]byte(`{"error":{"code":"unsupported_operation","message":"nope"}}`), &we); err != nil {
		t.Fatalf("decode: %v", err)
	}
	err := mapWireError(400, []byte(`{"error":{"code":"unsupported_operation","message":"nope"}}`))
	if !errors.Is(err, provider.ErrUnsupported) {
		t.Fatalf("unsupported_operation must map to ErrUnsupported: %v", err)
	}
	err = mapWireError(500, []byte(`{"error":{"code":"internal_error","message":"boom"}}`))
	if err == nil || !strings.Contains(err.Error(), "HTTP 500") {
		t.Fatalf("unknown codes surface as opaque wire errors: %v", err)
	}
}
