package health

import (
	"errors"
	"testing"
	"time"
)

func newTestBreaker() (*Breaker, *fakeClock) {
	c := &fakeClock{t: time.Date(2026, 3, 14, 12, 0, 0, 0, time.UTC)}
	b := NewBreaker("test", Config{Now: c.Now})
	return b, c
}

type fakeClock struct{ t time.Time }

func (c *fakeClock) Now() time.Time { return c.t }

func TestDefaultConfigMatchesSpec(t *testing.T) {
	cfg := DefaultConfig()
	if cfg.FailureThreshold != 5 {
		t.Errorf("FailureThreshold = %d, want 5", cfg.FailureThreshold)
	}
	if cfg.FailureWindow != 30*time.Second {
		t.Errorf("FailureWindow = %s, want 30s", cfg.FailureWindow)
	}
	if cfg.OpenTimeout != 60*time.Second {
		t.Errorf("OpenTimeout = %s, want 60s", cfg.OpenTimeout)
	}
	if cfg.ProbeLimit != 1 {
		t.Errorf("ProbeLimit = %d, want 1", cfg.ProbeLimit)
	}
}

func TestOpensAfterThresholdFailures(t *testing.T) {
	b, _ := newTestBreaker()
	for i := 0; i < 4; i++ {
		b.RecordFailure()
		if b.State() != StateClosed {
			t.Fatalf("breaker opened after %d failures (< threshold)", i+1)
		}
	}
	b.RecordFailure() // 5th within the 30s window
	if b.State() != StateOpen {
		t.Fatalf("breaker should be OPEN after 5 failures in window, got %s", b.State())
	}
	if err := b.Allow(); !errors.Is(err, ErrBreakerOpen) {
		t.Fatalf("Allow under OPEN should return ErrBreakerOpen, got %v", err)
	}
}

func TestFailuresOutsideWindowDoNotCount(t *testing.T) {
	b, c := newTestBreaker()
	for i := 0; i < 4; i++ {
		b.RecordFailure()
	}
	c.t = c.t.Add(31 * time.Second) // 4 failures fall out of the window
	b.RecordFailure()               // only 1 in-window failure
	if b.State() != StateClosed {
		t.Fatalf("breaker should stay CLOSED (1 in-window failure), got %s", b.State())
	}
	if b.FailureCount() != 1 {
		t.Fatalf("FailureCount = %d, want 1", b.FailureCount())
	}
}

func TestOpenTransitionsToHalfOpenAfterTimeout(t *testing.T) {
	b, c := newTestBreaker()
	trip := func() {
		for i := 0; i < 5; i++ {
			b.RecordFailure()
		}
	}
	trip()
	if b.State() != StateOpen {
		t.Fatalf("want OPEN, got %s", b.State())
	}

	c.t = c.t.Add(61 * time.Second) // past OpenTimeout
	if err := b.Allow(); err != nil {
		t.Fatalf("first call after OpenTimeout should be admitted as probe, got %v", err)
	}
	if b.State() != StateHalfOpen {
		t.Fatalf("want HALF_OPEN, got %s", b.State())
	}
	// ProbeLimit=1: a second concurrent call is still rejected
	if err := b.Allow(); !errors.Is(err, ErrBreakerOpen) {
		t.Fatalf("second call in HALF_OPEN should be rejected, got %v", err)
	}
}

func TestHalfOpenProbeSuccessCloses(t *testing.T) {
	b, c := newTestBreaker()
	for i := 0; i < 5; i++ {
		b.RecordFailure()
	}
	c.t = c.t.Add(61 * time.Second)
	if err := b.Allow(); err != nil {
		t.Fatalf("probe should be allowed: %v", err)
	}
	b.RecordSuccess()
	if b.State() != StateClosed {
		t.Fatalf("probe success should close the breaker, got %s", b.State())
	}
	if err := b.Allow(); err != nil {
		t.Fatalf("closed breaker should allow: %v", err)
	}
}

func TestHalfOpenProbeFailureReopens(t *testing.T) {
	b, c := newTestBreaker()
	for i := 0; i < 5; i++ {
		b.RecordFailure()
	}
	openedAt := c.t
	c.t = c.t.Add(61 * time.Second)
	if err := b.Allow(); err != nil {
		t.Fatalf("probe should be allowed: %v", err)
	}
	b.RecordFailure() // probe failed
	if b.State() != StateOpen {
		t.Fatalf("failed probe should re-open the breaker, got %s", b.State())
	}
	// and it stays open for a fresh full OpenTimeout
	c.t = c.t.Add(30 * time.Second)
	if err := b.Allow(); !errors.Is(err, ErrBreakerOpen) {
		t.Fatalf("still within new OpenTimeout, want rejection, got %v", err)
	}
	c.t = c.t.Add(31 * time.Second)
	if err := b.Allow(); err != nil {
		t.Fatalf("after full OpenTimeout probe should be allowed, got %v", err)
	}
	_ = openedAt
}

func TestSuccessResetsWindow(t *testing.T) {
	b, _ := newTestBreaker()
	for i := 0; i < 4; i++ {
		b.RecordFailure()
	}
	b.RecordSuccess()
	if b.State() != StateClosed || b.FailureCount() != 0 {
		t.Fatalf("success should clear failures, state=%s count=%d", b.State(), b.FailureCount())
	}
	for i := 0; i < 4; i++ {
		b.RecordFailure()
	}
	if b.State() != StateClosed {
		t.Fatalf("4 fresh failures should not trip (prior ones cleared), got %s", b.State())
	}
}

func TestReset(t *testing.T) {
	b, _ := newTestBreaker()
	for i := 0; i < 5; i++ {
		b.RecordFailure()
	}
	if b.State() != StateOpen {
		t.Fatalf("want OPEN, got %s", b.State())
	}
	b.Reset()
	if b.State() != StateClosed {
		t.Fatalf("Reset should force CLOSED, got %s", b.State())
	}
	if err := b.Allow(); err != nil {
		t.Fatalf("Allow after Reset should pass, got %v", err)
	}
}

func TestManagerPerNameBreakers(t *testing.T) {
	m := NewManager(Config{})
	a, b := m.Breaker("honeycoin"), m.Breaker("mpesa")
	if a == b {
		t.Fatal("manager must return distinct breakers per provider")
	}
	if m.Breaker("honeycoin") != a {
		t.Fatal("manager must be stable per name")
	}
	for i := 0; i < 5; i++ {
		a.RecordFailure()
	}
	if a.State() != StateOpen || b.State() != StateClosed {
		t.Fatalf("independent breakers: a=%s b=%s", a.State(), b.State())
	}
}
