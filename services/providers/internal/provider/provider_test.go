package provider

import (
	"context"
	"testing"
	"time"
)

func TestMoneyValidate(t *testing.T) {
	cases := []struct {
		m    Money
		bad  bool
		name string
	}{
		{Money{150000, "KES", 2}, false, "valid"},
		{Money{0, "USD", 2}, false, "zero amount is structurally fine"},
		{Money{1, "kes", 2}, true, "lowercase currency"},
		{Money{1, "KE", 2}, true, "short currency"},
		{Money{1, "KES", -1}, true, "negative exponent"},
		{Money{1, "KES", 13}, true, "exponent too large"},
		{Money{-1, "KES", 2}, true, "negative amount"},
	}
	for _, c := range cases {
		err := c.m.Validate()
		if c.bad && err == nil {
			t.Errorf("%s: expected error for %+v", c.name, c.m)
		}
		if !c.bad && err != nil {
			t.Errorf("%s: unexpected error %v", c.name, err)
		}
	}
}

func TestWindowValidate(t *testing.T) {
	from := time.Date(2026, 3, 14, 0, 0, 0, 0, time.UTC)
	if err := (Window{From: from, To: from.Add(time.Hour)}).Validate(); err != nil {
		t.Errorf("valid window rejected: %v", err)
	}
	if err := (Window{}).Validate(); err == nil {
		t.Error("zero window should be rejected")
	}
	if err := (Window{From: from.Add(time.Hour), To: from}).Validate(); err == nil {
		t.Error("inverted window should be rejected")
	}
}

type stubProvider struct{ name string }

func (s stubProvider) Name() string                                       { return s.name }
func (s stubProvider) Capabilities() Capabilities                         { return Capabilities{} }
func (s stubProvider) Quote(context.Context, QuoteRequest) (Quote, error) { return Quote{}, nil }
func (s stubProvider) Initiate(context.Context, InitiateRequest) (ProviderRef, error) {
	return ProviderRef{}, nil
}
func (s stubProvider) Poll(context.Context, ProviderRef) (TransferStatus, error) {
	return StatusPending, nil
}
func (s stubProvider) HandleCallback(context.Context, Callback) (TransferStatus, error) {
	return StatusPending, nil
}
func (s stubProvider) Cancel(context.Context, ProviderRef) error { return nil }
func (s stubProvider) Reverse(context.Context, ProviderRef) (ProviderRef, error) {
	return ProviderRef{}, nil
}
func (s stubProvider) ReconcileReport(context.Context, Window) ([]ProviderLine, error) {
	return nil, nil
}

// compile-time interface conformance guard.
var _ Provider = stubProvider{}

func TestRegistryRegisterGetList(t *testing.T) {
	r := NewRegistry()
	if err := r.Register(stubProvider{"honeycoin"}); err != nil {
		t.Fatalf("register: %v", err)
	}
	if err := r.Register(stubProvider{"mpesa"}); err != nil {
		t.Fatalf("register: %v", err)
	}
	if err := r.Register(stubProvider{"honeycoin"}); err == nil {
		t.Fatal("duplicate registration must be rejected")
	}
	if err := r.Register(nil); err == nil {
		t.Fatal("nil provider must be rejected")
	}
	if err := r.Register(stubProvider{""}); err == nil {
		t.Fatal("empty name must be rejected")
	}

	p, ok := r.Get("honeycoin")
	if !ok || p.Name() != "honeycoin" {
		t.Fatalf("Get(honeycoin) = %v, %v", p, ok)
	}
	if _, ok := r.Get("nope"); ok {
		t.Fatal("Get(nope) should miss")
	}
	if names := r.Names(); len(names) != 2 || names[0] != "honeycoin" || names[1] != "mpesa" {
		t.Fatalf("Names = %v, want sorted [honeycoin mpesa]", names)
	}
	if list := r.List(); len(list) != 2 || list[0].Name() != "honeycoin" {
		t.Fatalf("List = %v", list)
	}
}

func TestRegistryHealth(t *testing.T) {
	r := NewRegistry()
	if s := r.Health("ghost"); s != HealthUnknown {
		t.Fatalf("unregistered health = %s, want UNKNOWN", s)
	}
	if err := r.Register(stubProvider{"honeycoin"}); err != nil {
		t.Fatalf("register: %v", err)
	}
	if s := r.Health("honeycoin"); s != HealthUnknown {
		t.Fatalf("fresh health = %s, want UNKNOWN", s)
	}
	if err := r.SetHealth("honeycoin", HealthDegraded); err != nil {
		t.Fatalf("SetHealth: %v", err)
	}
	if s := r.Health("honeycoin"); s != HealthDegraded {
		t.Fatalf("health = %s, want DEGRADED", s)
	}
	if err := r.SetHealth("honeycoin", "BOGUS"); err == nil {
		t.Fatal("invalid state must be rejected")
	}
	if err := r.SetHealth("ghost", HealthHealthy); err == nil {
		t.Fatal("setting health for unregistered provider must be rejected")
	}
}
