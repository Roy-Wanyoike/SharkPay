package com.sharkpay.gateway.domain;

import java.time.Duration;

/**
 * Webhook retry policy (webhooks.yaml: "exponential backoff 1m → 1h, at
 * most 8 attempts"). Pure arithmetic, no clock.
 *
 * <ul>
 *   <li>wait before retry #n = 2^(n-1) minutes, capped at 60 minutes;</li>
 *   <li>after {@value #MAX_ATTEMPTS} failed attempts the delivery is dead
 *       (no ninth attempt is ever scheduled);</li>
 *   <li>the sequence is strictly monotonic up to the cap.</li>
 * </ul>
 */
public final class BackoffPolicy {

    /** Maximum send attempts per delivery (the 8th failure dead-letters). */
    public static final int MAX_ATTEMPTS = 8;

    /** Backoff cap (webhooks.yaml: 1m → 1h). */
    public static final Duration CAP = Duration.ofHours(1);

    private static final Duration BASE = Duration.ofMinutes(1);

    private BackoffPolicy() {
    }

    /**
     * The wait before the {@code nextAttemptNumber}th send (1-based: retry
     * 1 waits 1 m, retry 2 waits 2 m, ... capped at 1 h). Caller must not
     * ask for a retry beyond {@link #MAX_ATTEMPTS}.
     */
    public static Duration delayBeforeAttempt(int nextAttemptNumber) {
        if (nextAttemptNumber < 1 || nextAttemptNumber > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "attempt number must be within 1.." + MAX_ATTEMPTS + ": " + nextAttemptNumber);
        }
        Duration doubled = BASE.multipliedBy(1L << (nextAttemptNumber - 1));
        return capped(doubled);
    }

    /** Whether {@code failedAttempts} exhausted the policy (delivery is dead). */
    public static boolean exhausted(int failedAttempts) {
        return failedAttempts >= MAX_ATTEMPTS;
    }

    private static Duration capped(Duration delay) {
        return delay.compareTo(CAP) > 0 ? CAP : delay;
    }
}
