package com.sharkpay.gateway.domain;

import java.util.OptionalLong;

/**
 * Outcome of a quota check-and-consume: allowed, or exceeded with the
 * window that ran out and the {@code Retry-After} seconds until it resets
 * (docs/API-CONTRACTS.md §6 — 429 + Retry-After).
 */
public record QuotaDecision(boolean allowed, boolean monthly, long retryAfterSeconds) {

    /** Successful consumption. */
    public static QuotaDecision allow() {
        return new QuotaDecision(true, false, 0L);
    }

    /** The rpm (burst) window is exhausted. */
    public static QuotaDecision perMinuteExceeded(long retryAfterSeconds) {
        return new QuotaDecision(false, false, retryAfterSeconds);
    }

    /** The monthly (sustained) window is exhausted. */
    public static QuotaDecision monthlyExceeded(long retryAfterSeconds) {
        return new QuotaDecision(false, true, retryAfterSeconds);
    }

    /** Retry-After seconds when exceeded (empty when allowed). */
    public OptionalLong retryAfter() {
        return allowed ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
    }
}
