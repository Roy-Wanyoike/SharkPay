package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.AgingBucket;
import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Read side: the recon console's break list. Filters are optional and
 * composable; the aging filter judges the <b>live</b> bucket (recomputed
 * from detection time), so a break crosses buckets in the console the
 * moment it crosses them in the sweeper.
 */
public final class ListBreaksUseCase {

    private final ReconBreakRepository breaks;
    private final Clock clock;

    public ListBreaksUseCase(ReconBreakRepository breaks, Clock clock) {
        this.breaks = Objects.requireNonNull(breaks, "reconBreakRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param state    optional state filter (wire name: open, investigating,
     *                 resolved, compensated, waived)
     * @param aging    optional live aging filter (wire name: fresh, aging,
     *                 stale)
     * @param provider optional provider filter
     */
    public List<BreakView> list(String state, String aging, String provider) {
        BreakState stateFilter = state == null || state.isBlank() ? null
                : BreakState.fromWireName(state.trim().toLowerCase());
        AgingBucket agingFilter = aging == null || aging.isBlank() ? null
                : AgingBucket.fromWireName(aging.trim().toLowerCase());
        String providerFilter = provider == null || provider.isBlank() ? null : provider.trim();

        List<com.sharkpay.reconciliation.domain.ReconBreak> candidates;
        if (stateFilter != null) {
            candidates = breaks.listByState(stateFilter);
        } else {
            // unfiltered: active breaks first, then the terminal history
            java.util.ArrayList<com.sharkpay.reconciliation.domain.ReconBreak> all =
                    new java.util.ArrayList<>(breaks.listActive());
            all.addAll(breaks.listByState(BreakState.RESOLVED));
            all.addAll(breaks.listByState(BreakState.COMPENSATED));
            all.addAll(breaks.listByState(BreakState.WAIVED));
            candidates = all;
        }
        if (providerFilter != null) {
            candidates = candidates.stream()
                    .filter(break_ -> break_.provider().equals(providerFilter)).toList();
        }
        return candidates.stream()
                .map(break_ -> BreakView.of(break_, clock))
                .filter(view -> agingFilter == null || view.bucket() == agingFilter)
                .toList();
    }
}
