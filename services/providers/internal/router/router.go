// Package router selects the provider candidate for a money movement
// (ARCHITECTURE §4.1 / PRD D7 "router: policy engine scoring candidate
// providers per payment").
//
// Two stages:
//
//  1. HARD FILTERS (fail closed — a candidate failing any of these is
//     never selected):
//     - supports the requested currency?
//     - supports the requested rail?
//     - circuit breaker not open (health != OPEN)?
//     - capability fit (e.g. on_chain rail requires OnChain capability)?
//     - KYC tier gate (principal tier ≥ candidate MinTier, SECURITY §5:
//     "KYC tier gating capability matrix … payouts require limited+;
//     large limits require full")
//
//  2. SCORING (lower is better):
//
//     score = w1·costNorm + w2·latencyNorm + w3·healthPenalty
//
//     with documented default weights w1=0.5, w2=0.3, w3=0.2 (cost drives
//     unit economics, latency is second, health is tertiary), where
//     costNorm         = CostBps / max(CostBps over eligible, 1)
//     latencyNorm      = P99 / max(P99 over eligible, 1ns)
//     healthPenalty    = 0.0 HEALTHY, 0.5 DEGRADED, 0.75 UNKNOWN
//     Ties (|Δ| < 1e-12) break deterministically by candidate name so
//     routing is replayable — required for reconstruction and audits.
package router

import (
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
)

// ErrNoCandidate: no candidate survived the hard filters. Callers must not
// invent a provider — the payment fails closed.
var ErrNoCandidate = errors.New("sharkpay/router: no eligible provider candidate")

// Default weights (documented above; change via Config, keep deterministic).
const (
	DefaultWCost    = 0.5
	DefaultWLatency = 0.3
	DefaultWHealth  = 0.2
)

// Health penalties for scoring.
const (
	penaltyHealthy  = 0.0
	penaltyDegraded = 0.5
	penaltyUnknown  = 0.75
	penaltyOpen     = 1.0 // excluded by the hard filter; belt and braces
)

// Config holds the scoring weights. Zero fields fall back to defaults.
type Config struct {
	WCost    float64
	WLatency float64
	WHealth  float64
}

// DefaultConfig returns the documented default weights.
func DefaultConfig() Config {
	return Config{WCost: DefaultWCost, WLatency: DefaultWLatency, WHealth: DefaultWHealth}
}

func (c Config) withDefaults() Config {
	if c.WCost == 0 && c.WLatency == 0 && c.WHealth == 0 {
		return DefaultConfig()
	}
	if c.WCost == 0 {
		c.WCost = DefaultWCost
	}
	if c.WLatency == 0 {
		c.WLatency = DefaultWLatency
	}
	if c.WHealth == 0 {
		c.WHealth = DefaultWHealth
	}
	return c
}

// Candidate is a routable provider with the signals the router scores on.
// Candidates are built by the gateway from the registry (capability +
// health) plus the ops cost/latency table (real values from metrics —
// follow-up: feed from the observability stack, not static config).
type Candidate struct {
	Name    string
	Caps    provider.Capabilities
	CostBps int64         // total fee in basis points of the amount
	P99     time.Duration // observed p99 wire latency
	Health  provider.HealthState
	MinTier string // minimum KYC tier ("", "limited", "full"); "" = any
}

// Request is what the router routes for. Amount is carried for future
// amount-aware cost/tier policy (large payouts may require full KYC — the
// risk domain decides; the router stays a pure function of its inputs).
type Request struct {
	AmountMinor   int64
	Currency      string
	Rail          string
	PrincipalTier string // "unverified" | "limited" | "full"
}

// Router routes deterministically. Concurrency-safe (stateless).
type Router struct {
	cfg Config
}

// New builds a router; a zero Config means defaults.
func New(cfg Config) *Router {
	return &Router{cfg: cfg.withDefaults()}
}

// Config returns the effective router configuration.
func (r *Router) Config() Config { return r.cfg }

// tierRank orders KYC tiers (PRD D1: unverified → limited → full).
// Unknown strings rank lowest — fail closed.
func tierRank(tier string) int {
	switch tier {
	case "full":
		return 2
	case "limited":
		return 1
	default: // "", "unverified", anything unknown
		return 0
	}
}

// Eligible reports whether c survives the hard filters for req.
func Eligible(req Request, c Candidate) bool {
	if !c.Caps.SupportsCurrency(req.Currency) {
		return false
	}
	if !c.Caps.SupportsRail(req.Rail) {
		return false
	}
	if c.Health == provider.HealthOpen {
		// circuit breaker open — never route here
		return false
	}
	if req.Rail == "on_chain" && !c.Caps.OnChain {
		// capability fit: on-chain movements need an on-chain-capable provider
		return false
	}
	if tierRank(req.PrincipalTier) < tierRank(c.MinTier) {
		// KYC tier gate (SECURITY §5)
		return false
	}
	return true
}

// Select returns the best eligible candidate for req. With no eligible
// candidate it returns ErrNoCandidate (fail closed).
func (r *Router) Select(req Request, candidates []Candidate) (Candidate, error) {
	eligible := make([]Candidate, 0, len(candidates))
	for _, c := range candidates {
		if c.Name == "" {
			continue // malformed candidate — never routable
		}
		if Eligible(req, c) {
			eligible = append(eligible, c)
		}
	}
	if len(eligible) == 0 {
		return Candidate{}, fmt.Errorf("%w (currency=%s rail=%s tier=%s)",
			ErrNoCandidate, req.Currency, req.Rail, req.PrincipalTier)
	}

	// Normalization denominators over the eligible set.
	var maxCost int64 = 1
	var maxLat time.Duration = 1
	for _, c := range eligible {
		if c.CostBps > maxCost {
			maxCost = c.CostBps
		}
		if c.P99 > maxLat {
			maxLat = c.P99
		}
	}

	best := eligible[0]
	bestScore := r.score(best, maxCost, maxLat)
	for _, c := range eligible[1:] {
		s := r.score(c, maxCost, maxLat)
		if s < bestScore-1e-12 {
			best, bestScore = c, s
		} else if abs(s-bestScore) <= 1e-12 && c.Name < best.Name {
			// deterministic tiebreak by name (replayable routing)
			best, bestScore = c, s
		}
	}
	return best, nil
}

func (r *Router) score(c Candidate, maxCost int64, maxLat time.Duration) float64 {
	costNorm := float64(c.CostBps) / float64(maxCost)
	latNorm := float64(c.P99) / float64(maxLat)
	var hp float64
	switch c.Health {
	case provider.HealthHealthy:
		hp = penaltyHealthy
	case provider.HealthDegraded:
		hp = penaltyDegraded
	case provider.HealthOpen:
		hp = penaltyOpen
	default: // UNKNOWN
		hp = penaltyUnknown
	}
	return r.cfg.WCost*costNorm + r.cfg.WLatency*latNorm + r.cfg.WHealth*hp
}

// Rank returns eligible candidates ordered best-first (diagnostics; the
// ordering matches Select). Useful for fail-over lists.
func (r *Router) Rank(req Request, candidates []Candidate) ([]Candidate, error) {
	eligible := make([]Candidate, 0, len(candidates))
	for _, c := range candidates {
		if c.Name != "" && Eligible(req, c) {
			eligible = append(eligible, c)
		}
	}
	if len(eligible) == 0 {
		return nil, ErrNoCandidate
	}
	var maxCost int64 = 1
	var maxLat time.Duration = 1
	for _, c := range eligible {
		if c.CostBps > maxCost {
			maxCost = c.CostBps
		}
		if c.P99 > maxLat {
			maxLat = c.P99
		}
	}
	sort.SliceStable(eligible, func(i, j int) bool {
		si, sj := r.score(eligible[i], maxCost, maxLat), r.score(eligible[j], maxCost, maxLat)
		if abs(si-sj) <= 1e-12 {
			return eligible[i].Name < eligible[j].Name
		}
		return si < sj
	})
	return eligible, nil
}

func abs(f float64) float64 {
	if f < 0 {
		return -f
	}
	return f
}
