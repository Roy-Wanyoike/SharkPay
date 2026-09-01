package callback

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
)

const secret = "test-callback-secret"

// sign produces the headers HoneyCoin-style callbacks carry: HMAC-SHA256
// over ts "." body, hex encoded.
func sign(sec []byte, ts string, body []byte) map[string]string {
	mac := hmac.New(sha256.New, sec)
	mac.Write([]byte(ts))
	mac.Write([]byte("."))
	mac.Write(body)
	return map[string]string{
		HeaderTimestamp: ts,
		HeaderSignature: hex.EncodeToString(mac.Sum(nil)),
	}
}

func cb(providerName, ts string, body []byte, sec []byte) provider.Callback {
	return provider.Callback{Provider: providerName, Headers: sign(sec, ts, body), Body: body}
}

func fixedClock(t time.Time) VerifierConfig {
	return VerifierConfig{Now: func() time.Time { return t }}
}

func TestVerifyHappyPath(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	v := NewVerifier([]byte(secret), NewMemoryReplayCache(), fixedClock(now))
	body := []byte(`{"ref":"hct_123","status":"CONFIRMED"}`)
	res, err := v.Verify(cb("honeycoin", "1800000000", body, []byte(secret)))
	if err != nil || res != Verified {
		t.Fatalf("valid callback rejected: %v (result %s)", err, res)
	}
}

func TestVerifyForgedSignatureRejected(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	v := NewVerifier([]byte(secret), NewMemoryReplayCache(), fixedClock(now))
	// signed with the WRONG secret
	c := cb("honeycoin", "1800000000", []byte(`{"ref":"hct_1"}`), []byte("attacker"))
	if res, err := v.Verify(c); res != Rejected || !errors.Is(err, ErrBadSignature) {
		t.Fatalf("forged signature: res=%s err=%v", res, err)
	}
	// tampered body under a valid signature
	good := sign([]byte(secret), "1800000000", []byte(`{"ref":"hct_1","amount_minor":100}`))
	tampered := provider.Callback{Provider: "honeycoin", Headers: good, Body: []byte(`{"ref":"hct_1","amount_minor":1000000}`)}
	if res, err := v.Verify(tampered); res != Rejected || !errors.Is(err, ErrBadSignature) {
		t.Fatalf("tampered body: res=%s err=%v", res, err)
	}
	// non-hex signature
	bad := map[string]string{HeaderTimestamp: "1800000000", HeaderSignature: "not-hex!"}
	if res, err := v.Verify(provider.Callback{Provider: "honeycoin", Headers: bad, Body: []byte(`{}`)}); res != Rejected || !errors.Is(err, ErrBadSignature) {
		t.Fatalf("non-hex signature: res=%s err=%v", res, err)
	}
}

func TestVerifyStaleTimestampRejected(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	v := NewVerifier([]byte(secret), NewMemoryReplayCache(), fixedClock(now))
	// 6 minutes old (window is 5)
	old := fmt.Sprintf("%d", now.Add(-6*time.Minute).Unix())
	c := cb("honeycoin", old, []byte(`{"ref":"hct_1"}`), []byte(secret))
	if res, err := v.Verify(c); res != Rejected || !errors.Is(err, ErrStale) {
		t.Fatalf("stale callback: res=%s err=%v", res, err)
	}
	// 6 minutes in the future is equally stale
	future := fmt.Sprintf("%d", now.Add(6*time.Minute).Unix())
	c = cb("honeycoin", future, []byte(`{"ref":"hct_1"}`), []byte(secret))
	if res, err := v.Verify(c); res != Rejected || !errors.Is(err, ErrStale) {
		t.Fatalf("future callback: res=%s err=%v", res, err)
	}
	// just inside the window passes
	edge := fmt.Sprintf("%d", now.Add(-4*time.Minute).Unix())
	if res, err := v.Verify(cb("honeycoin", edge, []byte(`{"ref":"hct_2"}`), []byte(secret))); res != Verified || err != nil {
		t.Fatalf("edge-fresh callback: res=%s err=%v", res, err)
	}
}

func TestVerifyReplayRejected(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	// one controllable clock shared by cache AND verifier so TTL expiry and
	// freshness advance together
	clock := now
	clk := func() time.Time { return clock }
	cache := NewMemoryReplayCacheWithClock(clk)
	v := NewVerifier([]byte(secret), cache, VerifierConfig{Now: clk})
	body := []byte(`{"ref":"hct_9"}`)
	c := cb("honeycoin", "1800000000", body, []byte(secret))
	if res, err := v.Verify(c); res != Verified || err != nil {
		t.Fatalf("first delivery must pass: %v", err)
	}
	// exact replay: same ref, same timestamp, same signature
	if res, err := v.Verify(c); res != Rejected || !errors.Is(err, ErrReplay) {
		t.Fatalf("replay: res=%s err=%v", res, err)
	}
	// after TTL expiry (10 min) the nonce is forgotten; freshness must still
	// pass, so widen the window on the second verifier.
	clock = now.Add(11 * time.Minute)
	v2 := NewVerifier([]byte(secret), cache, VerifierConfig{
		Window: 15 * time.Minute,
		Now:    clk,
	})
	if res, err := v2.Verify(c); res != Verified || err != nil {
		t.Fatalf("post-TTL delivery must verify again: %v", err)
	}
}

func TestVerifyMalformed(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	v := NewVerifier([]byte(secret), NewMemoryReplayCache(), fixedClock(now))
	body := []byte(`{"ref":"hct_1"}`)

	// missing headers
	noHeaders := provider.Callback{Provider: "honeycoin", Headers: map[string]string{}, Body: body}
	if res, err := v.Verify(noHeaders); res != Rejected || !errors.Is(err, ErrMalformed) {
		t.Fatalf("missing headers: res=%s err=%v", res, err)
	}
	// undecodable timestamp
	badTS := map[string]string{HeaderTimestamp: "not-a-number", HeaderSignature: "ab"}
	if res, err := v.Verify(provider.Callback{Provider: "honeycoin", Headers: badTS, Body: body}); res != Rejected || !errors.Is(err, ErrMalformed) {
		t.Fatalf("bad timestamp: res=%s err=%v", res, err)
	}
	// valid signature but body carries no ref → malformed (cannot derive nonce)
	c := cb("honeycoin", "1800000000", []byte(`{"status":"CONFIRMED"}`), []byte(secret))
	if res, err := v.Verify(c); res != Rejected || !errors.Is(err, ErrMalformed) {
		t.Fatalf("no ref: res=%s err=%v", res, err)
	}
	// no secret configured → fail closed
	vNoSecret := NewVerifier(nil, NewMemoryReplayCache(), fixedClock(now))
	if res, err := vNoSecret.Verify(cb("honeycoin", "1800000000", body, []byte(secret))); res != Rejected || !errors.Is(err, ErrMalformed) {
		t.Fatalf("no secret: res=%s err=%v", res, err)
	}
}

func TestReplayNoncesAreDistinctPerProviderAndRef(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	v := NewVerifier([]byte(secret), NewMemoryReplayCache(), fixedClock(now))
	ts := "1800000000"
	// same timestamp + different ref → not a replay
	if res, err := v.Verify(cb("honeycoin", ts, []byte(`{"ref":"a"}`), []byte(secret))); res != Verified || err != nil {
		t.Fatalf("ref a: %v", err)
	}
	if res, err := v.Verify(cb("honeycoin", ts, []byte(`{"ref":"b"}`), []byte(secret))); res != Verified || err != nil {
		t.Fatalf("ref b (same ts, different ref must not be a replay): %v", err)
	}
}

func TestMemoryReplayCacheTTLAndPrune(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	c := NewMemoryReplayCacheWithClock(func() time.Time { return now })
	if c.SeenOrAdd("n1", time.Minute) {
		t.Fatal("first add must report unseen")
	}
	if !c.SeenOrAdd("n1", time.Minute) {
		t.Fatal("second add within TTL must report seen")
	}
	// advance clock past TTL
	now = now.Add(2 * time.Minute)
	if c.SeenOrAdd("n1", time.Minute) {
		t.Fatal("expired nonce must be forgotten")
	}
	c.Reset()
	if c.SeenOrAdd("n1", time.Minute) {
		t.Fatal("after Reset nonce must be unseen")
	}
}
