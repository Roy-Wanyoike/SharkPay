// Package replay implements the atomic mark-once nonce cache behind
// inbound-callback replay protection (SECURITY §4; API-CONTRACTS §4
// callback verification — WP-4's final item).
//
// The contract is a single operation:
//
//	AtomicallyMark(ctx, key, ttl) (seen bool, err error)
//
// "Mark-once" means the check and the store are ONE atomic step: there is
// no check-then-store race window in which two concurrent deliveries of
// the same nonce can both be accepted. Implementations:
//
//   - MemCache: in-process map guarded by a mutex (tests, sandbox and
//     single-instance deployments).
//   - RedisCache: Redis SET key 1 EX ttl NX — the atomicity lives in the
//     Redis server, so every pod pointing at the same Redis agrees on the
//     single winner (multi-pod production).
//
// Redis is never authoritative for money state (ARCHITECTURE §8 — losing
// the cache only risks duplicate processing, which downstream idempotency
// keys absorb). An unreachable Redis therefore DEGRADES LOUDLY instead of
// killing the process: requests fail closed with ErrUnavailable until the
// cache recovers; nothing is silently accepted unverified.
package replay

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// Cache is the atomic mark-once nonce store used by callback replay
// protection. Implementations must be safe for concurrent use.
type Cache interface {
	// AtomicallyMark records key for ttl and reports whether it was
	// already recorded (and not yet expired). seen=true ⇒ replay;
	// seen=false ⇒ this call performed the mark and owns the nonce.
	// err != nil ⇒ the cache could not be consulted or updated: callers
	// must fail closed, never treat the key as fresh.
	AtomicallyMark(ctx context.Context, key string, ttl time.Duration) (seen bool, err error)
}

// DefaultMaxEntries bounds MemCache growth so a nonce flood cannot grow
// the process without limit. Entries expire lazily; the hard bound is
// only reached when the map is FULL OF LIVE entries, in which case the
// soonest-expiring entries are evicted (see evictLocked).
const DefaultMaxEntries = 8192

// MemCache is the in-process Cache. Concurrency-safe: one mutex guards
// check+store, following the internal/store locking idiom. Entries expire
// lazily on access and opportunistically when the map hits its bound.
type MemCache struct {
	mu         sync.Mutex
	entries    map[string]time.Time // nonce → expires-at
	now        func() time.Time
	maxEntries int
}

// NewMemCache builds a MemCache on the wall clock with DefaultMaxEntries.
func NewMemCache() *MemCache {
	return NewMemCacheWithClock(time.Now)
}

// NewMemCacheWithClock builds a MemCache with an injectable clock (tests:
// TTL expiry without sleeping).
func NewMemCacheWithClock(now func() time.Time) *MemCache {
	return newMemCache(now, DefaultMaxEntries)
}

// NewMemCacheBounded builds a MemCache with an explicit entry bound
// (tests exercise eviction without 8192 marks). maxEntries < 1 falls back
// to DefaultMaxEntries.
func NewMemCacheBounded(now func() time.Time, maxEntries int) *MemCache {
	return newMemCache(now, maxEntries)
}

func newMemCache(now func() time.Time, maxEntries int) *MemCache {
	if now == nil {
		now = time.Now
	}
	if maxEntries < 1 {
		maxEntries = DefaultMaxEntries
	}
	return &MemCache{
		entries:    make(map[string]time.Time),
		now:        now,
		maxEntries: maxEntries,
	}
}

// AtomicallyMark implements Cache. The mutex makes check+store a single
// critical section: N concurrent marks of the same key yield exactly one
// seen=false.
func (c *MemCache) AtomicallyMark(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	if err := ctx.Err(); err != nil {
		// Cancelled before the mark: nothing was recorded — a later mark
		// of the same key can still win. Fail closed, let the caller retry.
		return false, fmt.Errorf("sharkpay/replay: mark %q: %w", key, err)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if exp, ok := c.entries[key]; ok {
		if now.Before(exp) {
			return true, nil // still marked — replay
		}
		delete(c.entries, key) // expired: treat as unseen
	}
	if len(c.entries) >= c.maxEntries {
		c.evictLocked(now)
	}
	c.entries[key] = now.Add(ttl)
	return false, nil
}

// evictLocked makes room when the map is at its bound. Step 1 drops every
// expired entry (lazy sweep). Step 2 — only if live entries still fill the
// map — evicts the entries expiring SOONEST: they are the closest to
// leaving the replay window anyway, so the extra replay exposure is
// minimal, bounded and documented (mark-once for a live key is otherwise
// guaranteed). O(n) in map size; it only runs at the bound.
func (c *MemCache) evictLocked(now time.Time) {
	for k, exp := range c.entries {
		if !now.Before(exp) {
			delete(c.entries, k)
		}
	}
	for len(c.entries) >= c.maxEntries {
		doomKey := ""
		doomExp := time.Time{}
		first := true
		for k, exp := range c.entries {
			if first || exp.Before(doomExp) {
				doomKey, doomExp, first = k, exp, false
			}
		}
		delete(c.entries, doomKey)
	}
}

// Reset clears all marks (test/ops tooling).
func (c *MemCache) Reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries = make(map[string]time.Time)
}
