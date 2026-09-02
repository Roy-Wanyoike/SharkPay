package com.sharkpay.payouts.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Background sweeper driving the payout lifecycle between API calls: every
 * tick releases one due batch, sweeps TTL expiries and polls one in-flight
 * batch. Scheduling is enabled on {@code PayoutsApplication}
 * ({@code @EnableScheduling}); the interval is configurable via
 * {@code payouts.scheduler.tick-interval-ms}. The same tick is exposed at
 * {@code POST /internal/payouts/scheduler/tick} for ops and integration
 * tests.
 *
 * <p><b>Temporal wiring point:</b> when the durable workflow layer lands
 * (ADR 001/002), per-payout workflows take over the timer-driven steps and
 * this sweeper becomes the reconciliation backstop only — the use-cases it
 * calls (release / expire / provider results) are exactly the activities a
 * workflow would signal, with identical ledger keys.</p>
 */
@Component
public final class PayoutSweeper {

    private final ReleaseDuePayoutsUseCase releaseDue;
    private final ExpirePayoutsUseCase expireOverdue;
    private final PollPayoutsUseCase pollInFlight;

    public PayoutSweeper(ReleaseDuePayoutsUseCase releaseDue, ExpirePayoutsUseCase expireOverdue,
                         PollPayoutsUseCase pollInFlight) {
        this.releaseDue = Objects.requireNonNull(releaseDue, "releaseDuePayoutsUseCase is required");
        this.expireOverdue = Objects.requireNonNull(expireOverdue,
                "expirePayoutsUseCase is required");
        this.pollInFlight = Objects.requireNonNull(pollInFlight, "pollPayoutsUseCase is required");
    }

    @Scheduled(fixedDelayString = "${payouts.scheduler.tick-interval-ms:5000}")
    public void tick() {
        runTick();
    }

    /** One full tick: release batch + TTL sweep + in-flight poll. */
    public TickReport runTick() {
        ReleaseDuePayoutsUseCase.Report release = releaseDue.releaseDue();
        ExpirePayoutsUseCase.Report expiry = expireOverdue.expireOverdue();
        PollPayoutsUseCase.Report poll = pollInFlight.pollInFlight();
        return new TickReport(release, expiry, poll);
    }

    /** The three sub-reports of one tick. */
    public record TickReport(ReleaseDuePayoutsUseCase.Report release,
                             ExpirePayoutsUseCase.Report expiry,
                             PollPayoutsUseCase.Report poll) {
    }
}
