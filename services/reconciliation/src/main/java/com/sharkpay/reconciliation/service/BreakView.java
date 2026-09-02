package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.AgingBucket;
import com.sharkpay.reconciliation.domain.ReconBreak;

import java.time.Clock;
import java.time.Duration;

/**
 * Read-side view of a break with its <b>live</b> aging (recomputed from
 * {@code detectedAt} on every read — the same pure function the sweeper
 * persists, so the console can never disagree with the escalation
 * pipeline).
 */
public record BreakView(ReconBreak break_, AgingBucket bucket, long ageHours) {

    public static BreakView of(ReconBreak break_, Clock clock) {
        java.time.Instant now = clock.instant();
        long ageHours = Math.max(0, Duration.between(break_.detectedAt(), now).toHours());
        return new BreakView(break_, AgingBucket.of(break_.detectedAt(), now), ageHours);
    }
}
