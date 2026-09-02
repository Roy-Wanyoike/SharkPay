package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.AgingBucket;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.ports.EventPublisher;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The RB-7 aging sweeper: recomputes the live aging bucket of every active
 * break (OPEN/INVESTIGATING), persists bucket transitions and publishes the
 * ops-alert event ({@code recon.break.escalated.v1}) exactly once per
 * transition — AGING is the RB-7 page (SECURITY §6 alert), STALE is the
 * S2-minimum escalation. Terminal breaks are never swept (aging stops at
 * resolution); repeated sweeps without a transition are no-ops.
 */
public final class SweepAgingBreaksUseCase {

    private final ReconBreakRepository breaks;
    private final EventPublisher events;
    private final ReconEvents eventFactory;
    private final Clock clock;

    public SweepAgingBreaksUseCase(ReconBreakRepository breaks, EventPublisher events,
                                   ReconEvents eventFactory, Clock clock) {
        this.breaks = Objects.requireNonNull(breaks, "reconBreakRepository is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.eventFactory = Objects.requireNonNull(eventFactory, "eventFactory is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public Result sweep() {
        List<ReconBreak> escalated = new ArrayList<>();
        for (ReconBreak break_ : breaks.listActive()) {
            AgingBucket live = AgingBucket.of(break_.detectedAt(), clock.instant());
            if (break_.advanceBucket(live, clock.instant())) {
                breaks.save(break_);
                escalated.add(break_);
                events.publish(eventFactory.breakEscalated(break_, live, clock.instant()));
            }
        }
        return new Result(escalated.size(), List.copyOf(escalated));
    }

    /**
     * @param escalatedCount number of bucket transitions (one ops alert each)
     * @param escalated      the breaks whose bucket advanced
     */
    public record Result(int escalatedCount, List<ReconBreak> escalated) {
    }
}
