package com.sharkpay.reconciliation.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Deterministic clock for tests: fixed, manually advanced (aging buckets,
 * escalation timing, run timestamps — RB-7's 24 h / 72 h boundaries are
 * driven through this clock).
 */
public final class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneOffset getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
