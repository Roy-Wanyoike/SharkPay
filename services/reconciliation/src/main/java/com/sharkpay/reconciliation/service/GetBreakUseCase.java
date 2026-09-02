package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Read side: one break with live aging. */
public final class GetBreakUseCase {

    private final ReconBreakRepository breaks;
    private final Clock clock;

    public GetBreakUseCase(ReconBreakRepository breaks, Clock clock) {
        this.breaks = Objects.requireNonNull(breaks, "reconBreakRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public BreakView get(String breakId) {
        ReconBreak break_ = breaks.findById(breakId)
                .orElseThrow(() -> new NoSuchElementException("recon break " + breakId + " not found"));
        return BreakView.of(break_, clock);
    }
}
