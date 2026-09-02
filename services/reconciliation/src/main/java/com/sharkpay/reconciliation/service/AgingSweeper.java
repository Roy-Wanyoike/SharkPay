package com.sharkpay.reconciliation.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Background sweeper that periodically re-ages open breaks and escalates
 * per RB-7. Scheduling is enabled on {@code ReconciliationApplication}
 * (@EnableScheduling); the interval is configurable via
 * {@code recon.aging-sweep-interval-ms}.
 */
@Component
public final class AgingSweeper {

    private final SweepAgingBreaksUseCase sweep;

    public AgingSweeper(SweepAgingBreaksUseCase sweep) {
        this.sweep = Objects.requireNonNull(sweep, "sweepAgingBreaksUseCase is required");
    }

    @Scheduled(fixedDelayString = "${recon.aging-sweep-interval-ms:300000}")
    public void sweep() {
        sweep.sweep();
    }
}
