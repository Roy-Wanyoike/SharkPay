package com.sharkpay.risk.domain;

import java.time.Duration;

/**
 * Velocity policy: at most {@code maxTransactions} money movements inside a
 * sliding {@code window} (default 10 per hour, docs/PRD.md D8).
 */
public record VelocityPolicy(int maxTransactions, Duration window) {

    public VelocityPolicy {
        if (maxTransactions < 1) {
            throw new IllegalArgumentException("maxTransactions must be >= 1, got " + maxTransactions);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive duration");
        }
    }
}
