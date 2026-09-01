package router

import (
	"errors"
	"testing"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/provider"
)

func caps(currencies ...string) provider.Capabilities {
	return provider.Capabilities{Currencies: currencies, Rails: []string{"honeycoin", "fake"}}
}

func baseCandidate(name string) Candidate {
	return Candidate{
		Name:   name,
		Caps:   caps("KES", "USD"),
		Health: provider.HealthHealthy,
	}
}

var baseReq = Request{AmountMinor: 150000, Currency: "KES", Rail: "honeycoin", PrincipalTier: "full"}

func TestSelectFiltersUnsupportedCurrency(t *testing.T) {
	r := New(DefaultConfig())
	onlyUSD := baseCandidate("usd-only")
	onlyUSD.Caps = caps("USD")
	both := baseCandidate("both")
	got, err := r.Select(baseReq, []Candidate{onlyUSD, both})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Name != "both" {
		t.Fatalf("expected both, got %s", got.Name)
	}
	// and with ONLY the wrong-currency candidate: fail closed
	_, err = r.Select(baseReq, []Candidate{onlyUSD})
	if !errors.Is(err, ErrNoCandidate) {
		t.Fatalf("expected ErrNoCandidate, got %v", err)
	}
}

func TestSelectFiltersUnsupportedRail(t *testing.T) {
	r := New(DefaultConfig())
	// Health equal, costs equal → tiebreak would pick "a"; but "a" lacks the rail.
	noRail := baseCandidate("a")
	noRail.Caps = caps("KES")
	noRail.Caps.Rails = []string{"mpesa"}
	withRail := baseCandidate("b")
	got, err := r.Select(baseReq, []Candidate{noRail, withRail})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Name != "b" {
		t.Fatalf("expected b (rail-capable), got %s", got.Name)
	}
	_, err = r.Select(baseReq, []Candidate{noRail})
	if !errors.Is(err, ErrNoCandidate) {
		t.Fatalf("expected ErrNoCandidate, got %v", err)
	}
}

func TestSelectFiltersOpenBreaker(t *testing.T) {
	r := New(DefaultConfig())
	open := baseCandidate("open")
	open.Health = provider.HealthOpen
	healthy := baseCandidate("healthy")
	got, err := r.Select(baseReq, []Candidate{open, healthy})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Name != "healthy" {
		t.Fatalf("expected healthy, got %s", got.Name)
	}
	_, err = r.Select(baseReq, []Candidate{open})
	if !errors.Is(err, ErrNoCandidate) {
		t.Fatalf("expected ErrNoCandidate for open breaker, got %v", err)
	}
}

func TestSelectFiltersOnChainCapabilityFit(t *testing.T) {
	r := New(DefaultConfig())
	req := baseReq
	req.Rail = "on_chain"

	notOnChain := baseCandidate("legacy")
	notOnChain.Caps = caps("KES")
	notOnChain.Caps.Rails = []string{"honeycoin", "on_chain"}
	notOnChain.Caps.OnChain = false

	onChain := baseCandidate("chain")
	onChain.Caps = caps("KES")
	onChain.Caps.Rails = []string{"honeycoin", "on_chain"}
	onChain.Caps.OnChain = true

	got, err := r.Select(req, []Candidate{notOnChain, onChain})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Name != "chain" {
		t.Fatalf("expected chain (on_chain capable), got %s", got.Name)
	}
	_, err = r.Select(req, []Candidate{notOnChain})
	if !errors.Is(err, ErrNoCandidate) {
		t.Fatalf("expected ErrNoCandidate without on-chain capability, got %v", err)
	}
}

func TestSelectFiltersTierGate(t *testing.T) {
	r := New(DefaultConfig())
	req := baseReq
	req.PrincipalTier = "unverified"

	gated := baseCandidate("gated")
	gated.MinTier = "full" // e.g. large-limit / on-chain tier floor

	ungated := baseCandidate("ungated")
	got, err := r.Select(req, []Candidate{gated, ungated})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Name != "ungated" {
		t.Fatalf("expected ungated, got %s", got.Name)
	}
	_, err = r.Select(req, []Candidate{gated})
	if !errors.Is(err, ErrNoCandidate) {
		t.Fatalf("expected ErrNoCandidate below MinTier, got %v", err)
	}

	// limited satisfies a limited floor
	gated.MinTier = "limited"
	req.PrincipalTier = "limited"
	if _, err := r.Select(req, []Candidate{gated}); err != nil {
		t.Fatalf("limited should satisfy MinTier=limited: %v", err)
	}
}

func TestScoringCostDominates(t *testing.T) {
	r := New(DefaultConfig())
	cheapSlow := baseCandidate("cheap-slow")
	cheapSlow.CostBps = 10
	cheapSlow.P99 = 2 * time.Second
	expensiveFast := baseCandidate("expensive-fast")
	expensiveFast.CostBps = 200
	expensiveFast.P99 = 100 * time.Millisecond

	got, err := r.Select(baseReq, []Candidate{cheapSlow, expensiveFast})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Name != "cheap-slow" {
		t.Fatalf("expected cheap-slow to win (w1=0.5 cost dominates), got %s", got.Name)
	}
}

func TestScoringHealthPenaltyFlips(t *testing.T) {
	r := New(DefaultConfig())
	healthy := baseCandidate("healthy")
	healthy.CostBps = 200
	healthy.P99 = 100 * time.Millisecond
	healthy.Health = provider.HealthHealthy

	degraded := baseCandidate("degraded")
	degraded.CostBps = 170
	degraded.P99 = 100 * time.Millisecond
	degraded.Health = provider.HealthDegraded

	// A modest 30 bps cost advantage (0.5·0.15 = 0.075) cannot overcome the
	// degraded health penalty (0.2·0.5 = 0.1): healthy = 0.8 < degraded = 0.825.
	got, _ := r.Select(baseReq, []Candidate{healthy, degraded})
	if got.Name != "healthy" {
		t.Fatalf("expected healthy, got %s", got.Name)
	}

	// A large cost gap DOES overcome the penalty — but here the cheaper one is
	// healthy anyway, so healthy wins on both terms.
	healthy.CostBps = 150
	degraded.CostBps = 200
	got, _ = r.Select(baseReq, []Candidate{healthy, degraded})
	if got.Name != "healthy" {
		t.Fatalf("expected healthy-cheap, got %s", got.Name)
	}

	// Unknown health is penalized harder (0.75) than degraded (0.5): with
	// equal costs the unknown candidate loses.
	unknown := degraded
	unknown.Name = "zz-unknown"
	unknown.Health = provider.HealthUnknown
	unknown.CostBps = 200
	unknown.P99 = 100 * time.Millisecond
	degraded.CostBps = 150
	degraded.Health = provider.HealthDegraded
	// unknown: 0.5·1+0.3+0.2·0.75 = 0.95; degraded: 0.5·0.75+0.3+0.1 = 0.775.
	got, _ = r.Select(baseReq, []Candidate{unknown, degraded})
	if got.Name != "degraded" {
		t.Fatalf("expected degraded (unknown penalized), got %s", got.Name)
	}
}

func TestScoringLatencyBreaksNearTie(t *testing.T) {
	r := New(DefaultConfig())
	fast := baseCandidate("fast")
	fast.CostBps = 100
	fast.P99 = 100 * time.Millisecond
	slow := baseCandidate("slow")
	slow.CostBps = 100
	slow.P99 = 900 * time.Millisecond

	got, err := r.Select(baseReq, []Candidate{fast, slow})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Name != "fast" {
		t.Fatalf("expected fast, got %s", got.Name)
	}
}

func TestSelectTiebreakByName(t *testing.T) {
	r := New(DefaultConfig())
	a := baseCandidate("alpha")
	b := baseCandidate("beta")
	// identical everything → deterministic name order
	got, err := r.Select(baseReq, []Candidate{b, a})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Name != "alpha" {
		t.Fatalf("expected deterministic tiebreak to alpha, got %s", got.Name)
	}
}

func TestSelectNoCandidates(t *testing.T) {
	r := New(DefaultConfig())
	if _, err := r.Select(baseReq, nil); !errors.Is(err, ErrNoCandidate) {
		t.Fatalf("expected ErrNoCandidate for empty list, got %v", err)
	}
}

func TestRankMatchesSelect(t *testing.T) {
	r := New(DefaultConfig())
	cheapSlow := baseCandidate("cheap-slow")
	cheapSlow.CostBps = 10
	cheapSlow.P99 = 2 * time.Second
	expensiveFast := baseCandidate("expensive-fast")
	expensiveFast.CostBps = 200
	expensiveFast.P99 = 100 * time.Millisecond
	opened := baseCandidate("opened")
	opened.Health = provider.HealthOpen

	ranked, err := r.Rank(baseReq, []Candidate{opened, expensiveFast, cheapSlow})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(ranked) != 2 {
		t.Fatalf("expected 2 ranked candidates, got %d", len(ranked))
	}
	if ranked[0].Name != "cheap-slow" || ranked[1].Name != "expensive-fast" {
		t.Fatalf("unexpected ranking order: %v then %v", ranked[0].Name, ranked[1].Name)
	}
}
