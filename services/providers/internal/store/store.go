// Package store defines the adapter_calls audit trail persistence
// (DATA-MODEL §3.5: providers own "adapter_calls (full request/response
// audit, redacted)"). SECURITY §1 lists the trail as the repudiation
// control; NFR-06 requires 100% of financial writes audited.
//
// Production uses the append-only PostgreSQL table in the providers
// schema. This package ships the interface plus an in-memory
// implementation for tests, sandbox and single-process operation.
package store

import (
	"sync"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
)

// AuditStore persists the adapter_calls audit trail. Implementations must
// be safe for concurrent use and must not silently drop records.
type AuditStore interface {
	// Append records one adapter call. The adapter has already redacted
	// Request/Response before calling Append.
	Append(call provider.AdapterCall) error
	// Calls returns the recorded calls in append order.
	Calls() []provider.AdapterCall
}

// MemoryStore is the in-memory AuditStore. Retention is unbounded —
// suitable for tests, sandbox and short-lived processes only; production
// must use the PostgreSQL implementation (retention ≥ 7 years, immutable,
// exported to WORM daily per SECURITY §5/§6).
type MemoryStore struct {
	mu    sync.RWMutex
	calls []provider.AdapterCall
	seq   int
}

// NewMemoryStore returns an empty in-memory audit store.
func NewMemoryStore() *MemoryStore {
	return &MemoryStore{}
}

// Append implements AuditStore.
func (s *MemoryStore) Append(call provider.AdapterCall) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.seq++
	if call.ID == "" {
		call.ID = "mem-" + itoa(s.seq)
	}
	s.calls = append(s.calls, call)
	return nil
}

// Calls implements AuditStore; the returned slice is a copy.
func (s *MemoryStore) Calls() []provider.AdapterCall {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]provider.AdapterCall, len(s.calls))
	copy(out, s.calls)
	return out
}

// Reset clears recorded calls (test/ops tooling).
func (s *MemoryStore) Reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls = nil
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}
