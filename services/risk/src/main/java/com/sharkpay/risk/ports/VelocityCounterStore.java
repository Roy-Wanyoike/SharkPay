package com.sharkpay.risk.ports;

import com.sharkpay.money.Money;

import java.time.Duration;
import java.time.Instant;

/**
 * Windowed counter port shared by the velocity rule (transaction counts) and
 * the tier-limit rule (rolling 24h/7d amount sums).
 *
 * <p>Ordering contract: {@link #record} is called by the evaluation use-case
 * <b>only when the final decision is ALLOW</b>, after the engine has run
 * (increment-and-check: rules read with the query methods, the engine never
 * writes). Denied or review-parked transactions never enter the counters,
 * and idempotent replays of an already-recorded evaluation do not re-record.</p>
 *
 * <p>Implementations own the clock: the in-memory fake takes an injectable
 * {@link java.time.Clock} (tests drive time explicitly); the JPA adapter
 * buckets the counters table on its own clock.</p>
 */
public interface VelocityCounterStore {

    /**
     * Number of recorded transactions of {@code subject} inside the sliding
     * window ending now (window start inclusive).
     */
    int countInWindow(String subject, Duration window);

    /**
     * Sum of recorded amounts of {@code subject} in {@code currency} inside
     * the sliding window ending now. Zero of the requested currency when
     * nothing was recorded.
     */
    Money amountInWindow(String subject, String currency, Duration window);

    /** Records one allowed transaction (count and amount, per currency). */
    void record(String subject, Money amount, Instant occurredAt);
}
