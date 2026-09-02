package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.ReconRun;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;
import com.sharkpay.reconciliation.ports.ReconRunRepository;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Read side: one run with its breaks, each carrying live aging. */
public final class GetReconRunUseCase {

    private final ReconRunRepository runs;
    private final ReconBreakRepository breaks;
    private final Clock clock;

    public GetReconRunUseCase(ReconRunRepository runs, ReconBreakRepository breaks, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "reconRunRepository is required");
        this.breaks = Objects.requireNonNull(breaks, "reconBreakRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public Result get(String runId) {
        ReconRun run = runs.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("recon run " + runId + " not found"));
        List<BreakView> breakViews = breaks.listByRun(runId).stream()
                .map(break_ -> BreakView.of(break_, clock))
                .toList();
        return new Result(run, breakViews);
    }

    public record Result(ReconRun run, List<BreakView> breaks) {
    }
}
