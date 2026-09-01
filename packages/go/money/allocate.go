package money

import (
	"fmt"
	"math/bits"
	"sort"
)

// Allocate splits m into len(ratios) parts proportional to the ratios.
//
// ratios must be non-negative integers that sum exactly to total, which acts
// as the denominator of the split: Allocate([]int{1, 2}, 3) splits into
// thirds, Allocate([]int{50, 50}, 100) splits in half.
//
// Parts are computed with the largest-remainder method: each part is the
// floor of its exact share, and the units left over are handed out one by
// one to the parts with the largest fractional remainders (ties broken by
// lower index, so the result is deterministic). Consequently:
//
//   - the parts always sum to m exactly — no minor unit is lost or created
//     (critical for split payments and fee distribution);
//   - each part is within one minor unit of its exact proportional share;
//   - a ratio of 0 always yields a zero part;
//   - for non-negative amounts, a strictly larger ratio never yields a
//     smaller part.
//
// Negative amounts are allocated on their magnitude and negated part by
// part, preserving the exact-sum invariant. Invalid inputs (empty ratios,
// non-positive total, negative ratios, ratios not summing to total) return
// ErrInvalidRatios; nothing is ever partially allocated.
func (m Money) Allocate(ratios []int, total int) ([]Money, error) {
	if len(ratios) == 0 {
		return nil, fmt.Errorf("%w: no ratios given", ErrInvalidRatios)
	}
	if total <= 0 {
		return nil, fmt.Errorf("%w: total must be positive, got %d", ErrInvalidRatios, total)
	}
	sum := 0
	for i, r := range ratios {
		if r < 0 {
			return nil, fmt.Errorf("%w: ratios[%d] is negative (%d)", ErrInvalidRatios, i, r)
		}
		// Incremental check also guards against int overflow.
		if sum > total-r {
			return nil, fmt.Errorf("%w: ratios sum above total %d", ErrInvalidRatios, total)
		}
		sum += r
	}
	if sum != total {
		return nil, fmt.Errorf("%w: ratios sum to %d, want total %d", ErrInvalidRatios, sum, total)
	}

	negative := m.AmountMinor < 0
	magnitude := uint64(m.AmountMinor)
	if negative {
		magnitude = -magnitude // |AmountMinor|, correct also for MinInt64
	}

	denominator := uint64(total)
	quotients := make([]uint64, len(ratios))
	remainders := make([]uint64, len(ratios))
	var sumQuotients uint64
	for i, r := range ratios {
		// 128-bit multiply then divide: magnitude*r/denominator without
		// overflow. bits.Div64 is safe here because magnitude <= 2^63 and
		// r <= total imply hi < denominator.
		hi, lo := bits.Mul64(magnitude, uint64(r))
		q, rem := bits.Div64(hi, lo, denominator)
		quotients[i] = q
		remainders[i] = rem
		sumQuotients += q
	}
	// leftover is the number of leftover minor units; it is provably
	// < len(ratios) because the ratios sum exactly to total.
	leftover := magnitude - sumQuotients

	order := make([]int, len(ratios))
	for i := range order {
		order[i] = i
	}
	// Largest remainder first; the stable sort keeps lower indices ahead on
	// ties.
	sort.SliceStable(order, func(a, b int) bool {
		return remainders[order[a]] > remainders[order[b]]
	})
	for i := uint64(0); i < leftover; i++ {
		quotients[order[i]]++
	}

	parts := make([]Money, len(ratios))
	for i, q := range quotients {
		amount := int64(q)
		if negative {
			// For q == 2^63 (only reachable when m == MinInt64 and one part
			// takes the whole magnitude) the wrap lands exactly on MinInt64.
			amount = -int64(q)
		}
		parts[i] = Money{AmountMinor: amount, Currency: m.Currency, Exponent: m.Exponent}
	}
	return parts, nil
}
