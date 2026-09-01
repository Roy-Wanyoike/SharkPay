package store

import (
	"sync"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
)

func TestMemoryStoreAppendAndOrder(t *testing.T) {
	s := NewMemoryStore()
	for i := 0; i < 3; i++ {
		if err := s.Append(provider.AdapterCall{ID: "c1", Provider: "honeycoin", Method: "Initiate", Outcome: provider.OutcomeSuccess}); err != nil {
			t.Fatalf("append: %v", err)
		}
	}
	calls := s.Calls()
	if len(calls) != 3 {
		t.Fatalf("len = %d, want 3", len(calls))
	}
}

func TestMemoryStoreAssignsIDs(t *testing.T) {
	s := NewMemoryStore()
	if err := s.Append(provider.AdapterCall{Provider: "honeycoin", Method: "Poll"}); err != nil {
		t.Fatalf("append: %v", err)
	}
	if s.Calls()[0].ID == "" {
		t.Fatal("store should assign an id when the adapter did not set one")
	}
}

func TestMemoryStoreReset(t *testing.T) {
	s := NewMemoryStore()
	_ = s.Append(provider.AdapterCall{Method: "Quote"})
	s.Reset()
	if len(s.Calls()) != 0 {
		t.Fatalf("Reset should clear the store, got %d calls", len(s.Calls()))
	}
}

func TestMemoryStoreCallsIsACopy(t *testing.T) {
	s := NewMemoryStore()
	_ = s.Append(provider.AdapterCall{Method: "Quote"})
	calls := s.Calls()
	calls[0].Method = "mutated"
	if s.Calls()[0].Method == "mutated" {
		t.Fatal("Calls must return a copy, not the internal slice")
	}
}

func TestMemoryStoreConcurrentAppends(t *testing.T) {
	s := NewMemoryStore()
	const goroutines, perG = 16, 64
	var wg sync.WaitGroup
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < perG; i++ {
				_ = s.Append(provider.AdapterCall{
					Provider: "honeycoin", Method: "Initiate",
					Latency: time.Millisecond, Outcome: provider.OutcomeSuccess,
				})
			}
		}()
	}
	wg.Wait()
	if got := len(s.Calls()); got != goroutines*perG {
		t.Fatalf("lost writes: got %d calls, want %d", got, goroutines*perG)
	}
}
