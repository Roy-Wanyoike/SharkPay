package provider

import (
	"fmt"
	"sort"
	"sync"
)

// HealthState is the router-facing health of a registered provider.
//
// The registry stores the last known state; a health monitor (breaker
// events + periodic probes) keeps it fresh in production. Until that
// monitor lands, wiring code / ops sets states via SetHealth. The router
// hard-filters OPEN candidates and penalizes DEGRADED/UNKNOWN in scoring.
type HealthState string

const (
	// HealthHealthy: serving normally.
	HealthHealthy HealthState = "HEALTHY"
	// HealthDegraded: serving with elevated errors/latency (e.g. breaker
	// tripping soon, half-open probing).
	HealthDegraded HealthState = "DEGRADED"
	// HealthOpen: circuit breaker open — the router must not select this
	// provider.
	HealthOpen HealthState = "OPEN"
	// HealthUnknown: no signal yet (boot, monitor gap).
	HealthUnknown HealthState = "UNKNOWN"
)

// Valid reports whether s is a known HealthState.
func (s HealthState) Valid() bool {
	switch s {
	case HealthHealthy, HealthDegraded, HealthOpen, HealthUnknown:
		return true
	}
	return false
}

// Registry holds the registered providers and their health states.
// It is concurrency-safe. One registry per provider gateway process.
type Registry struct {
	mu        sync.RWMutex
	providers map[string]Provider
	health    map[string]HealthState
}

// NewRegistry returns an empty registry.
func NewRegistry() *Registry {
	return &Registry{
		providers: make(map[string]Provider),
		health:    make(map[string]HealthState),
	}
}

// Register adds p. A provider with an empty name or a duplicate name is
// rejected — names are the routing key for every downstream dispatch.
func (r *Registry) Register(p Provider) error {
	if p == nil {
		return fmt.Errorf("registry: cannot register nil provider")
	}
	name := p.Name()
	if name == "" {
		return fmt.Errorf("registry: provider name must not be empty")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, dup := r.providers[name]; dup {
		return fmt.Errorf("registry: provider %q already registered", name)
	}
	r.providers[name] = p
	r.health[name] = HealthUnknown
	return nil
}

// Get returns the provider registered under name.
func (r *Registry) Get(name string) (Provider, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.providers[name]
	return p, ok
}

// List returns all registered providers sorted by name (deterministic).
func (r *Registry) List() []Provider {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Provider, 0, len(r.providers))
	for _, p := range r.providers {
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name() < out[j].Name() })
	return out
}

// Names returns the registered provider names, sorted.
func (r *Registry) Names() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	names := make([]string, 0, len(r.providers))
	for name := range r.providers {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// SetHealth records the health state of a registered provider.
func (r *Registry) SetHealth(name string, state HealthState) error {
	if !state.Valid() {
		return fmt.Errorf("registry: unknown health state %q", state)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.providers[name]; !ok {
		return fmt.Errorf("registry: cannot set health for unknown provider %q", name)
	}
	r.health[name] = state
	return nil
}

// Health returns the provider's health state; unregistered or unseen
// providers report HealthUnknown.
func (r *Registry) Health(name string) HealthState {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if s, ok := r.health[name]; ok {
		return s
	}
	return HealthUnknown
}
