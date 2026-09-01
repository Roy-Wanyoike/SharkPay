package money

import (
	"errors"
	"math"
	"math/big"
	"math/rand"
	"testing"
)

func TestAllocateTable(t *testing.T) {
	tests := []struct {
		name     string
		amount   int64
		currency string
		ratios   []int
		total    int
		want     []int64
	}{
		{name: "half and half", amount: 10000, currency: "KES", ratios: []int{1, 1}, total: 2, want: []int64{5000, 5000}},
		{name: "thirds", amount: 10000, currency: "KES", ratios: []int{1, 2}, total: 3, want: []int64{3333, 6667}},
		{name: "single part takes all", amount: 10000, currency: "KES", ratios: []int{7}, total: 7, want: []int64{10000}},
		{name: "one cent into thirds", amount: 1, currency: "KES", ratios: []int{1, 1, 1}, total: 3, want: []int64{1, 0, 0}},
		{name: "five cents into thirds", amount: 5, currency: "KES", ratios: []int{1, 1, 1}, total: 3, want: []int64{2, 2, 1}},
		{name: "percent split", amount: 10000, currency: "KES", ratios: []int{30, 70}, total: 100, want: []int64{3000, 7000}},
		{name: "zero ratio gets nothing", amount: 10000, currency: "KES", ratios: []int{0, 1}, total: 1, want: []int64{0, 10000}},
		{name: "zero amount", amount: 0, currency: "KES", ratios: []int{1, 3}, total: 4, want: []int64{0, 0}},
		{name: "negative amount mirrors positive", amount: -10000, currency: "KES", ratios: []int{1, 2}, total: 3, want: []int64{-3333, -6667}},
		{name: "stablecoin micro split", amount: 1000001, currency: "USDC", ratios: []int{1, 1}, total: 2, want: []int64{500001, 500000}},
		{name: "remainder to larger share", amount: 10000, currency: "KES", ratios: []int{2, 1, 1}, total: 4, want: []int64{5000, 2500, 2500}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			m := mustNew(t, tc.amount, tc.currency)
			got, err := m.Allocate(tc.ratios, tc.total)
			if err != nil {
				t.Fatalf("Allocate(%v, %d) error: %v", tc.ratios, tc.total, err)
			}
			if len(got) != len(tc.ratios) {
				t.Fatalf("got %d parts, want %d", len(got), len(tc.ratios))
			}
			for i, part := range got {
				if part.Currency != m.Currency || part.Exponent != m.Exponent {
					t.Fatalf("part %d = %+v, currency/exponent mismatch with %+v", i, part, m)
				}
				if part.AmountMinor != tc.want[i] {
					t.Errorf("part %d = %d, want %d (parts: %v)", i, part.AmountMinor, tc.want[i], amounts(got))
				}
			}
		})
	}
}

func amounts(parts []Money) []int64 {
	out := make([]int64, len(parts))
	for i, p := range parts {
		out[i] = p.AmountMinor
	}
	return out
}

func TestAllocateErrors(t *testing.T) {
	m := mustNew(t, 10000, "KES")
	tests := []struct {
		name   string
		ratios []int
		total  int
	}{
		{name: "no ratios", ratios: nil, total: 10},
		{name: "empty ratios", ratios: []int{}, total: 10},
		{name: "zero total", ratios: []int{1, 1}, total: 0},
		{name: "negative total", ratios: []int{1, 1}, total: -2},
		{name: "negative ratio", ratios: []int{1, -1}, total: 0},
		{name: "ratios below total", ratios: []int{1, 2}, total: 6},
		{name: "ratios above total", ratios: []int{2, 5}, total: 6},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			parts, err := m.Allocate(tc.ratios, tc.total)
			if !errors.Is(err, ErrInvalidRatios) {
				t.Fatalf("Allocate(%v, %d) error = %v, want ErrInvalidRatios", tc.ratios, tc.total, err)
			}
			if parts != nil {
				t.Fatalf("Allocate(%v, %d) returned parts on error: %v", tc.ratios, tc.total, parts)
			}
		})
	}
}

func TestAllocateExtremes(t *testing.T) {
	// The full-magnitude part of MinInt64 exercises the int64 wrap edge.
	t.Run("MinInt64 single part", func(t *testing.T) {
		m := mustNew(t, math.MinInt64, "KES")
		parts, err := m.Allocate([]int{1}, 1)
		if err != nil {
			t.Fatalf("Allocate error: %v", err)
		}
		if len(parts) != 1 || parts[0].AmountMinor != math.MinInt64 {
			t.Fatalf("parts = %v, want [%d]", amounts(parts), math.MinInt64)
		}
	})

	t.Run("MinInt64 in thirds", func(t *testing.T) {
		m := mustNew(t, math.MinInt64, "KES")
		parts, err := m.Allocate([]int{1, 1, 1}, 3)
		if err != nil {
			t.Fatalf("Allocate error: %v", err)
		}
		checkAllocationInvariants(t, m, []int{1, 1, 1}, 3, parts)
	})

	t.Run("MinInt64 in 1:2", func(t *testing.T) {
		m := mustNew(t, math.MinInt64, "KES")
		parts, err := m.Allocate([]int{1, 2}, 3)
		if err != nil {
			t.Fatalf("Allocate error: %v", err)
		}
		checkAllocationInvariants(t, m, []int{1, 2}, 3, parts)
	})

	t.Run("MaxInt64 in thirds", func(t *testing.T) {
		m := mustNew(t, math.MaxInt64, "KES")
		parts, err := m.Allocate([]int{1, 1, 1}, 3)
		if err != nil {
			t.Fatalf("Allocate error: %v", err)
		}
		checkAllocationInvariants(t, m, []int{1, 1, 1}, 3, parts)
	})
}

// checkAllocationInvariants asserts the full Allocate contract for the
// given inputs: exact sum, per-part proximity to the exact share, zero
// ratios mapping to zero parts and monotonicity for non-negative amounts.
func checkAllocationInvariants(t *testing.T, m Money, ratios []int, total int, parts []Money) {
	t.Helper()
	if len(parts) != len(ratios) {
		t.Fatalf("got %d parts, want %d", len(parts), len(ratios))
	}

	sum, err := Zero(m.Currency)
	if err != nil {
		t.Fatalf("Zero error: %v", err)
	}
	for i, part := range parts {
		if part.Currency != m.Currency || part.Exponent != m.Exponent {
			t.Fatalf("part %d = %+v does not match currency/exponent of %+v", i, part, m)
		}
		if m.AmountMinor > 0 && part.AmountMinor < 0 {
			t.Fatalf("part %d = %d is negative for positive amount %d", i, part.AmountMinor, m.AmountMinor)
		}
		if m.AmountMinor < 0 && part.AmountMinor > 0 {
			t.Fatalf("part %d = %d is positive for negative amount %d", i, part.AmountMinor, m.AmountMinor)
		}
		if ratios[i] == 0 && part.AmountMinor != 0 {
			t.Fatalf("zero ratio produced part %d = %d", i, part.AmountMinor)
		}
		sum, err = sum.Add(part)
		if err != nil {
			t.Fatalf("summing parts failed at %d: %v", i, err)
		}
	}
	if !sum.Equal(m) {
		t.Fatalf("sum of parts = %d, want %d (ratios %v, total %d)", sum.AmountMinor, m.AmountMinor, ratios, total)
	}

	// Each part must be within one minor unit of its exact share, computed
	// with exact rational arithmetic.
	totalBig := big.NewInt(int64(total))
	for i, part := range parts {
		exactNum := new(big.Int).Mul(big.NewInt(m.AmountMinor), big.NewInt(int64(ratios[i])))
		share := new(big.Rat).SetFrac(exactNum, totalBig)
		diff := new(big.Rat).SetInt64(part.AmountMinor)
		diff.Sub(diff, share)
		diff.Abs(diff)
		if diff.Cmp(big.NewRat(1, 1)) > 0 {
			t.Fatalf("part %d = %d differs from exact share %v by more than 1 minor unit", i, part.AmountMinor, share)
		}
	}

	// Monotonicity (non-negative amounts only): a strictly larger ratio
	// never yields a smaller part.
	if m.AmountMinor > 0 {
		for x := range parts {
			for y := range parts {
				if ratios[x] > ratios[y] && parts[x].AmountMinor < parts[y].AmountMinor {
					t.Fatalf("ratio %d produced part %d smaller than ratio %d's part %d",
						ratios[x], parts[x].AmountMinor, ratios[y], parts[y].AmountMinor)
				}
			}
		}
	}
}

// TestAllocateProperty runs 200 random allocations across all currencies,
// part counts and ratio compositions, asserting the full invariant set each
// time.
func TestAllocateProperty(t *testing.T) {
	rng := rand.New(rand.NewSource(20260901))
	currencies := SupportedCurrencies()
	for i := 0; i < 200; i++ {
		currency := currencies[rng.Intn(len(currencies))]

		var amount int64
		switch rng.Intn(6) {
		case 0:
			amount = int64(rng.Intn(1_000_000)) + 1
		case 1:
			amount = -(int64(rng.Intn(1_000_000)) + 1)
		case 2:
			amount = rng.Int63()
		case 3:
			amount = -rng.Int63()
		case 4:
			amount = 0
		default:
			amount = 1 + rng.Int63n(1<<50)
		}
		m := mustNew(t, amount, currency)

		partsCount := 1 + rng.Intn(8)
		total := 1 + rng.Intn(1000)
		ratios := splitTotal(rng, total, partsCount)

		parts, err := m.Allocate(ratios, total)
		if err != nil {
			t.Fatalf("iteration %d: Allocate(%v, %d) on %v error: %v", i, ratios, total, m, err)
		}
		checkAllocationInvariants(t, m, ratios, total, parts)
	}
}

// TestAllocatePropertyTinyAmounts focuses on amounts smaller than the number
// of parts, where the largest-remainder distribution does all the work.
func TestAllocatePropertyTinyAmounts(t *testing.T) {
	rng := rand.New(rand.NewSource(99))
	for i := 0; i < 200; i++ {
		currency := []string{"KES", "USDC"}[rng.Intn(2)]
		amount := int64(rng.Intn(10)) // 0..9 minor units
		m := mustNew(t, amount, currency)
		partsCount := 2 + rng.Intn(6)
		total := 1 + rng.Intn(50)
		ratios := splitTotal(rng, total, partsCount)
		parts, err := m.Allocate(ratios, total)
		if err != nil {
			t.Fatalf("iteration %d: Allocate(%v, %d) error: %v", i, ratios, total, err)
		}
		checkAllocationInvariants(t, m, ratios, total, parts)
	}
}

// splitTotal randomly composes total into n non-negative integers
// (a uniform composition).
func splitTotal(rng *rand.Rand, total, n int) []int {
	if n == 1 {
		return []int{total}
	}
	ratios := make([]int, n)
	remaining := total
	for i := 0; i < n-1; i++ {
		take := rng.Intn(remaining + 1)
		ratios[i] = take
		remaining -= take
	}
	ratios[n-1] = remaining
	return ratios
}
