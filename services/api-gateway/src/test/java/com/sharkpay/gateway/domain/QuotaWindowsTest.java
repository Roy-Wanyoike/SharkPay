package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quota window arithmetic: UTC-fixed minute and calendar-month buckets and
 * the Retry-After computation (docs/API-CONTRACTS.md §6). Boundaries are
 * computed from the request instant so a mutable clock can pin them.
 */
class QuotaWindowsTest {

    @Test
    void minuteWindowsTruncateToTheUtcMinute() {
        Instant now = Instant.parse("2026-09-01T10:00:37.512Z");
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), QuotaWindows.minuteStart(now));
        assertEquals(Instant.parse("2026-09-01T10:01:00Z"), QuotaWindows.minuteEnd(now));
    }

    @Test
    void theExactMinuteBoundaryBelongsToItsOwnWindow() {
        Instant onTheDot = Instant.parse("2026-09-01T10:01:00Z");
        assertEquals(Instant.parse("2026-09-01T10:01:00Z"), QuotaWindows.minuteStart(onTheDot));
        assertEquals(Instant.parse("2026-09-01T10:02:00Z"), QuotaWindows.minuteEnd(onTheDot));
    }

    @Test
    void monthWindowsCoverWholeUtcCalendarMonths() {
        Instant midSeptember = Instant.parse("2026-09-15T23:59:59Z");
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), QuotaWindows.monthStart(midSeptember));
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), QuotaWindows.monthEnd(midSeptember));

        // the first instant of the month starts a fresh window
        Instant firstInstant = Instant.parse("2026-09-01T00:00:00Z");
        assertEquals(firstInstant, QuotaWindows.monthStart(firstInstant));
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), QuotaWindows.monthEnd(firstInstant));
    }

    @Test
    void yearRolloverRollsTheMonthWindow() {
        Instant endOfYear = LocalDateTime.of(2026, 12, 31, 23, 59, 59)
                .toInstant(ZoneOffset.UTC);
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), QuotaWindows.monthStart(endOfYear));
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), QuotaWindows.monthEnd(endOfYear));
    }

    @Test
    void februaryIsHandledIncludingLeapYears() {
        LocalDate leapFeb = LocalDate.of(2028, 2, 10);
        Instant now = leapFeb.atStartOfDay(ZoneOffset.UTC).toInstant();
        assertEquals(Instant.parse("2028-02-01T00:00:00Z"), QuotaWindows.monthStart(now));
        assertEquals(Instant.parse("2028-03-01T00:00:00Z"), QuotaWindows.monthEnd(now));
    }

    @Test
    void retryAfterIsWholeSecondsFlooredAndNeverZero() {
        Instant now = Instant.parse("2026-09-01T10:00:37.512Z");
        Instant windowEnd = QuotaWindows.minuteEnd(now);
        // 22.488 s left → floor 22
        assertEquals(22L, QuotaWindows.retryAfterSeconds(now, windowEnd));

        // 1 ms left → floor 0, but a client must never be told "retry in 0 s"
        Instant almost = windowEnd.minusMillis(1);
        assertEquals(1L, QuotaWindows.retryAfterSeconds(almost, windowEnd));

        // exactly on the boundary → 0 real seconds, still clamped to 1
        assertEquals(1L, QuotaWindows.retryAfterSeconds(windowEnd, windowEnd));
    }

    @Test
    void retryAfterOfAFutureMinuteWindowIsAtMostTheMinuteLength() {
        Instant now = Instant.parse("2026-09-01T10:00:00Z");
        assertEquals(60L, QuotaWindows.retryAfterSeconds(now, QuotaWindows.minuteEnd(now)));
    }

    @Test
    void monthRetryAfterSpansDays() {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        long seconds = QuotaWindows.retryAfterSeconds(now, QuotaWindows.monthEnd(now));
        // Sept 15 → Oct 1 spans 16 days (30 - 15 + 1)
        assertEquals(Duration.ofDays(16).toSeconds(), seconds);
    }
}
