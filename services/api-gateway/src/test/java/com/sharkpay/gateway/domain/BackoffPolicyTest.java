package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry schedule: 1m, 2m, 4m, ... capped at 1h, exactly
 * {@value BackoffPolicy#MAX_ATTEMPTS} attempts, then dead.
 */
class BackoffPolicyTest {

    @Test
    void retryScheduleIsOneTwoFourCappedAtOneHour() {
        assertEquals(Duration.ofMinutes(1), BackoffPolicy.delayBeforeAttempt(1));
        assertEquals(Duration.ofMinutes(2), BackoffPolicy.delayBeforeAttempt(2));
        assertEquals(Duration.ofMinutes(4), BackoffPolicy.delayBeforeAttempt(3));
        assertEquals(Duration.ofMinutes(8), BackoffPolicy.delayBeforeAttempt(4));
        assertEquals(Duration.ofMinutes(16), BackoffPolicy.delayBeforeAttempt(5));
        assertEquals(Duration.ofMinutes(32), BackoffPolicy.delayBeforeAttempt(6));
        // 64m and 128m are both capped at the 1h ceiling
        assertEquals(Duration.ofHours(1), BackoffPolicy.delayBeforeAttempt(7));
        assertEquals(Duration.ofHours(1), BackoffPolicy.delayBeforeAttempt(8));
    }

    @Test
    void retryScheduleIsMonotonic() {
        List<Duration> delays = new ArrayList<>();
        for (int attempt = 1; attempt <= BackoffPolicy.MAX_ATTEMPTS; attempt++) {
            delays.add(BackoffPolicy.delayBeforeAttempt(attempt));
        }
        for (int i = 1; i < delays.size(); i++) {
            assertTrue(delays.get(i - 1).compareTo(delays.get(i)) <= 0,
                    "delay must never shrink: " + delays);
        }
    }

    @Test
    void capIsExactlyOneHour() {
        assertEquals(Duration.ofHours(1), BackoffPolicy.CAP);
        for (int attempt = 1; attempt <= BackoffPolicy.MAX_ATTEMPTS; attempt++) {
            assertTrue(BackoffPolicy.delayBeforeAttempt(attempt).compareTo(BackoffPolicy.CAP) <= 0);
        }
    }

    @Test
    void exactlyEightAttemptsThenExhausted() {
        assertEquals(8, BackoffPolicy.MAX_ATTEMPTS);
        for (int failed = 0; failed < 8; failed++) {
            boolean exhausted = BackoffPolicy.exhausted(failed);
            assertEquals(failed >= 8, exhausted, "attempts=" + failed);
        }
        assertTrue(BackoffPolicy.exhausted(8));
        assertFalse(BackoffPolicy.exhausted(7));
        assertTrue(BackoffPolicy.exhausted(9));
    }

    @Test
    void attemptNumbersOutsideThePolicyAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BackoffPolicy.delayBeforeAttempt(0));
        assertThrows(IllegalArgumentException.class, () -> BackoffPolicy.delayBeforeAttempt(9));
        assertThrows(IllegalArgumentException.class, () -> BackoffPolicy.delayBeforeAttempt(-1));
    }
}
