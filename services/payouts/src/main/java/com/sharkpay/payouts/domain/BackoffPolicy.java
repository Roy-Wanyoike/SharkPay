package com.sharkpay.payouts.domain;

import com.sharkpay.payouts.ports.Randomness;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded exponential backoff with jitter for provider submission retries:
 *
 * <pre>delay(attempts) = min(cap, base * 2^attempts) + jitter(0..jitterBound)</pre>
 *
 * Properties (money-safety adjacent — retry storms are an outage vector):
 * <ul>
 *   <li><b>bounded:</b> the exponential component never exceeds {@code cap}
 *       and the jitter never exceeds {@code jitterBound} ≤ cap/8;</li>
 *   <li><b>monotonic:</b> with zero jitter the sequence is non-decreasing in
 *       the attempt number; with jitter, each delay lies within
 *       [schedule(n), schedule(n) + jitterBound] where the underlying
 *       schedule is monotonic and capped;</li>
 *   <li><b>deterministic:</b> jitter comes from the {@link Randomness} port,
 *       so tests pin exact sequences.</li>
 * </ul>
 */
public final class BackoffPolicy {

    private final Duration base;
    private final Duration cap;
    private final Duration jitterBound;

    public BackoffPolicy(Duration base, Duration cap, Duration jitterBound) {
        this.base = Objects.requireNonNull(base, "base is required");
        this.cap = Objects.requireNonNull(cap, "cap is required");
        this.jitterBound = jitterBound == null ? Duration.ZERO : jitterBound;
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("backoff base must be positive");
        }
        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("backoff cap must be >= base");
        }
        if (this.jitterBound.isNegative()) {
            throw new IllegalArgumentException("jitter bound must be non-negative");
        }
        if (this.jitterBound.compareTo(cap.dividedBy(8)) > 0) {
            throw new IllegalArgumentException(
                    "jitter bound must be at most cap/8: " + this.jitterBound);
        }
    }

    /**
     * The delay to wait before attempt {@code attempts + 1}
     * ({@code attempts} counts the failures so far, starting at 0 for the
     * first retry after the initial failure).
     */
    public Duration nextBackoff(int attempts, Randomness randomness) {
        Objects.requireNonNull(randomness, "randomness is required");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be non-negative: " + attempts);
        }
        long exponential = cappedExponentialMs(attempts);
        long jitter = jitterBound.isZero()
                ? 0L
                : randomness.bounded(jitterBound.toMillis() + 1);
        return Duration.ofMillis(exponential + jitter);
    }

    /** The deterministic exponential component: min(cap, base * 2^attempts). */
    public long cappedExponentialMs(int attempts) {
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be non-negative: " + attempts);
        }
        long capMs = cap.toMillis();
        long baseMs = base.toMillis();
        // shift guarded: base << attempts must never wrap long before the
        // min() can clamp it — a wrapped (negative) delay is an
        // immediate-retry storm. Saturate at the cap whenever the shift
        // would exceed the representable range (attempts >= 63, or base
        // larger than the bits left).
        if (attempts >= 63 || baseMs > (Long.MAX_VALUE >> attempts)) {
            return capMs;
        }
        long doubled = baseMs << attempts;
        return Math.min(capMs, doubled);
    }

    public Duration base() {
        return base;
    }

    public Duration cap() {
        return cap;
    }

    public Duration jitterBound() {
        return jitterBound;
    }
}
