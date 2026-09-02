package com.sharkpay.gateway.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Pure quota window arithmetic for the per-key rpm + monthly quotas
 * (docs/API-CONTRACTS.md §6). Fixed UTC windows: a minute bucket covers
 * [minuteStart, minuteStart + 1m); the monthly bucket covers the UTC
 * calendar month. Window boundaries are computed from the request instant —
 * the instant the clock says, never {@code System.currentTimeMillis()}
 * directly, so tests can pin the boundary with a mutable clock.
 */
public final class QuotaWindows {

    private QuotaWindows() {
    }

    /** Start of the UTC minute containing {@code now}. */
    public static Instant minuteStart(Instant now) {
        return now.truncatedTo(ChronoUnit.MINUTES);
    }

    /** Exclusive end of the UTC minute containing {@code now}. */
    public static Instant minuteEnd(Instant now) {
        return minuteStart(now).plusSeconds(60);
    }

    /** Start of the UTC calendar month containing {@code now}. */
    public static Instant monthStart(Instant now) {
        LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return YearMonth.from(date).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Exclusive end of the UTC calendar month containing {@code now}. */
    public static Instant monthEnd(Instant now) {
        LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return YearMonth.from(date).plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Whole seconds until the window closes (the {@code Retry-After} value on
     * 429s); always at least 1 so a client cannot be told "retry in 0 s" and
     * spin.
     */
    public static long retryAfterSeconds(Instant now, Instant windowEnd) {
        long seconds = Math.floorDiv(windowEnd.toEpochMilli() - now.toEpochMilli(), 1000L);
        return Math.max(1L, seconds);
    }
}
