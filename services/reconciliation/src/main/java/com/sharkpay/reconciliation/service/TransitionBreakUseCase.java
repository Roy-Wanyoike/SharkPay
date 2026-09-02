package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Manual break lifecycle transitions (RB-7): OPEN → INVESTIGATING →
 * RESOLVED | WAIVED. Every transition records the acting principal and a
 * note (the hypothesis). The COMPENSATED state is deliberately not
 * reachable here — it is driven exclusively by compensation execution
 * (4-eyes), keeping the money-moving path on one auditable rail.
 */
public final class TransitionBreakUseCase {

    private final ReconBreakRepository breaks;
    private final Clock clock;

    public TransitionBreakUseCase(ReconBreakRepository breaks, Clock clock) {
        this.breaks = Objects.requireNonNull(breaks, "reconBreakRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param breakId   the break
     * @param toState   target wire name: investigating, resolved, waived
     * @param principal the acting operator
     * @param note      the RB-7 hypothesis (required, ≤ 500 chars)
     */
    public BreakView transition(String breakId, String toState, String principal, String note) {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("principal must not be blank");
        }
        ReconBreak break_ = breaks.findById(breakId)
                .orElseThrow(() -> new NoSuchElementException("recon break " + breakId + " not found"));
        String target = toState == null ? "" : toState.trim().toLowerCase();
        switch (target) {
            case "investigating" -> break_.startInvestigation(principal.trim(), note, clock.instant());
            case "resolved" -> break_.resolve(principal.trim(), note, clock.instant());
            case "waived" -> break_.waive(principal.trim(), note, clock.instant());
            case "compensated" -> throw new com.sharkpay.reconciliation.domain.ReconciliationStateException(
                    "break " + breakId + " can only reach compensated via a 4-eyes compensation "
                            + "execution (POST /internal/recon/compensations/{id}/approve)");
            default -> throw new IllegalArgumentException(
                    "unknown break state '" + toState + "' (legal manual targets: investigating, "
                            + "resolved, waived)");
        }
        breaks.save(break_);
        return BreakView.of(break_, clock);
    }
}
