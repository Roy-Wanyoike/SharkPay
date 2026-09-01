// Package callback verifies INBOUND provider callbacks using the
// API-CONTRACTS §4 signature family applied to the provider→SharkPay
// direction:
//
//	headers: X-SharkPay-Timestamp  — unix seconds
//	         X-SharkPay-Signature  — hex HMAC-SHA256(timestamp "." raw body, secret)
//	freshness: timestamp within ±5 minutes of now
//	replay:    nonce = provider "|" ref "|" timestamp, cached for 10 minutes
//
// Verification order: signature (constant-time, crypto/hmac) → freshness →
// replay. A signature failure is logged/alerted per SECURITY §6
// (webhook/callback signature failures are detection signals).
//
// ReplayCache is an interface: production backs it with Redis (ElastiCache,
// ARCHITECTURE §8 — never authoritative for money state; losing the cache
// only risks duplicate processing, which downstream idempotency keys
// absorb). This package ships the in-memory TTL implementation used by
// tests, sandbox and single-instance deployments; the Redis
// implementation is a follow-up.
package callback

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
)

// Inbound callback headers (scheme above).
const (
	HeaderTimestamp = "X-SharkPay-Timestamp"
	HeaderSignature = "X-SharkPay-Signature"
)

// Sentinel verification errors. Verify wraps these with %w; callers match
// with errors.Is.
var (
	// ErrStale: timestamp outside the ±window.
	ErrStale = errors.New("sharkpay/callback: timestamp outside allowed window")
	// ErrBadSignature: HMAC mismatch (or undecodable signature).
	ErrBadSignature = errors.New("sharkpay/callback: signature verification failed")
	// ErrReplay: nonce already seen within the replay TTL.
	ErrReplay = errors.New("sharkpay/callback: replayed callback")
	// ErrMalformed: missing headers, undecodable timestamp, missing ref,
	// or no secret configured.
	ErrMalformed = errors.New("sharkpay/callback: malformed callback")
)

// Result is the verification outcome.
type Result int

const (
	// Verified: the callback is authentic, fresh and not a replay.
	Verified Result = iota
	// Rejected: verification failed; consult the returned error sentinel.
	Rejected
)

func (r Result) String() string {
	if r == Verified {
		return "verified"
	}
	return "rejected"
}

// ReplayCache stores seen nonces for a TTL.
type ReplayCache interface {
	// SeenOrAdd reports whether nonce was already recorded; otherwise it
	// records nonce for ttl and returns false.
	SeenOrAdd(nonce string, ttl time.Duration) bool
}

// MemoryReplayCache is the in-memory TTL implementation. Concurrency-safe.
// Entries expire lazily on access; expired entries are pruned opportunistically.
type MemoryReplayCache struct {
	mu      sync.Mutex
	entries map[string]time.Time // nonce → expires-at
	now     func() time.Time
}

// NewMemoryReplayCache builds a cache using the wall clock.
func NewMemoryReplayCache() *MemoryReplayCache {
	return NewMemoryReplayCacheWithClock(time.Now)
}

// NewMemoryReplayCacheWithClock builds a cache with an injectable clock
// (tests: TTL expiry without sleeping).
func NewMemoryReplayCacheWithClock(now func() time.Time) *MemoryReplayCache {
	return &MemoryReplayCache{entries: make(map[string]time.Time), now: now}
}

// SeenOrAdd implements ReplayCache.
func (c *MemoryReplayCache) SeenOrAdd(nonce string, ttl time.Duration) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if exp, ok := c.entries[nonce]; ok {
		if now.Before(exp) {
			return true // replay within TTL
		}
		delete(c.entries, nonce) // expired: treat as unseen
	}
	// opportunistic prune: keep the map bounded under key churn
	if len(c.entries) > 1024 {
		for k, exp := range c.entries {
			if !now.Before(exp) {
				delete(c.entries, k)
			}
		}
	}
	c.entries[nonce] = now.Add(ttl)
	return false
}

// Reset clears the cache (test/ops tooling).
func (c *MemoryReplayCache) Reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries = make(map[string]time.Time)
}

// VerifierConfig tunes the verifier; zero values become the documented
// defaults (±5 min window, 10 min replay TTL, wall clock).
type VerifierConfig struct {
	Window    time.Duration
	ReplayTTL time.Duration
	Now       func() time.Time // tests
}

// Verifier authenticates inbound callbacks for one provider secret.
type Verifier struct {
	secret    []byte
	cache     ReplayCache
	window    time.Duration
	replayTTL time.Duration
	now       func() time.Time
}

// NewVerifier builds a Verifier. A nil cache defaults to an in-memory
// replay cache (single-instance deployments; use Redis for multi-pod prod).
func NewVerifier(secret []byte, cache ReplayCache, cfg VerifierConfig) *Verifier {
	if cache == nil {
		cache = NewMemoryReplayCache()
	}
	if cfg.Window <= 0 {
		cfg.Window = 5 * time.Minute
	}
	if cfg.ReplayTTL <= 0 {
		cfg.ReplayTTL = 10 * time.Minute
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	return &Verifier{
		secret:    secret,
		cache:     cache,
		window:    cfg.Window,
		replayTTL: cfg.ReplayTTL,
		now:       cfg.Now,
	}
}

// Verify authenticates cb: constant-time signature compare over
// timestamp "." raw body, ±window freshness, then replay protection with
// nonce = provider "|" ref "|" timestamp.
//
// On success it returns (Verified, nil). On failure it returns
// (Rejected, err) where err wraps ErrBadSignature, ErrStale, ErrReplay or
// ErrMalformed. The callback body is NOT trusted until Verified.
func (v *Verifier) Verify(cb provider.Callback) (Result, error) {
	if len(v.secret) == 0 {
		// fail closed on misconfiguration
		return Rejected, fmt.Errorf("%w: no callback secret configured", ErrMalformed)
	}

	tsStr := strings.TrimSpace(cb.Headers[HeaderTimestamp])
	sigHex := strings.TrimSpace(cb.Headers[HeaderSignature])
	if tsStr == "" || sigHex == "" {
		return Rejected, fmt.Errorf("%w: missing %s/%s headers", ErrMalformed, HeaderTimestamp, HeaderSignature)
	}
	ts, err := strconv.ParseInt(tsStr, 10, 64)
	if err != nil {
		return Rejected, fmt.Errorf("%w: bad timestamp %q", ErrMalformed, tsStr)
	}
	sig, err := hex.DecodeString(sigHex)
	if err != nil {
		return Rejected, fmt.Errorf("%w: signature is not valid hex", ErrBadSignature)
	}

	// 1) signature — constant-time compare over ts "." raw body.
	mac := hmac.New(sha256.New, v.secret)
	mac.Write([]byte(tsStr))
	mac.Write([]byte("."))
	mac.Write(cb.Body)
	if !hmac.Equal(mac.Sum(nil), sig) {
		return Rejected, ErrBadSignature
	}

	// 2) freshness — ±window around now.
	now := v.now()
	delta := now.Sub(time.Unix(ts, 0))
	if delta < 0 {
		delta = -delta
	}
	if delta > v.window {
		return Rejected, fmt.Errorf("%w: %.0fs off (window %.0fs)", ErrStale, delta.Seconds(), v.window.Seconds())
	}

	// 3) replay — nonce derives from provider + ref + timestamp.
	ref := extractRef(cb.Body)
	if ref == "" {
		return Rejected, fmt.Errorf("%w: callback body has no ref", ErrMalformed)
	}
	nonce := cb.Provider + "|" + ref + "|" + tsStr
	if v.cache.SeenOrAdd(nonce, v.replayTTL) {
		return Rejected, fmt.Errorf("%w: nonce %q seen within %s", ErrReplay, nonce, v.replayTTL)
	}
	return Verified, nil
}

// extractRef pulls the "ref" field out of the callback envelope. The
// envelope shape (JSON with a top-level "ref") is the SharkPay-side
// inbound contract shared by callback-emitting providers.
func extractRef(body []byte) string {
	var env struct {
		Ref string `json:"ref"`
	}
	if err := json.Unmarshal(body, &env); err != nil {
		return ""
	}
	return env.Ref
}
