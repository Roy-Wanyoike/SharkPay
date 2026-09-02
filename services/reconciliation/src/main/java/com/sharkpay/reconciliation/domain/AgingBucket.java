package com.sharkpay.reconciliation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * RB-7 aging buckets for an unresolved break, computed from the detection
 * time (the break row's {@code detected_at}):
 *
 * <table border="1">
 *   <caption>RB-7 aging posture</caption>
 *   <tr><th>Age</th><th>Bucket</th><th>Required action</th></tr>
 *   <tr><td>{@code age &lt; 24h}</td><td>{@code FRESH}</td>
 *       <td>auto-recon retry (timing skew is common)</td></tr>
 *   <tr><td>{@code 24h ≤ age ≤ 72h}</td><td>{@code AGING}</td>
 *       <td><b>page</b> (SECURITY §6 alert); named owner + hypothesis</td></tr>
 *   <tr><td>{@code age &gt; 72h}</td><td>{@code STALE}</td>
 *       <td>escalated — S2 minimum; S1 on funds-loss suspicion</td></tr>
 * </table>
 *
 * <p>{@link #of(Instant, Instant)} is pure so the aging sweeper and the
 * read side always agree; bucket boundaries are exact (24h and 72h sharp
 * land in AGING).</p>
 */
public enum AgingBucket {

    FRESH("fresh"),
    AGING("aging"),
    STALE("stale");

    /** Age at which a break leaves FRESH (RB-7). */
    public static final Duration FRESH_LIMIT = Duration.ofHours(24);

    /** Age at which a break becomes STALE (RB-7: "greater than 72 h"). */
    public static final Duration STALE_THRESHOLD = Duration.ofHours(72);

    private final String wireName;

    AgingBucket(String wireName) {
        this.wireName = wireName;
    }

    /** The wire/API/DB name of the bucket. */
    public String wireName() {
        return wireName;
    }

    /** Parses the wire name (storage/API); never guesses. */
    public static AgingBucket fromWireName(String wireName) {
        for (AgingBucket bucket : values()) {
            if (bucket.wireName.equals(wireName)) {
                return bucket;
            }
        }
        throw new IllegalArgumentException("unknown aging bucket: " + wireName);
    }

    /**
     * The bucket a break detected at {@code detectedAt} is in at
     * {@code now}: FRESH below 24 h, AGING in [24 h, 72 h], STALE above
     * 72 h.
     */
    public static AgingBucket of(Instant detectedAt, Instant now) {
        Objects.requireNonNull(detectedAt, "detectedAt is required");
        Objects.requireNonNull(now, "now is required");
        Duration age = Duration.between(detectedAt, now);
        if (age.isNegative()) {
            // clock skew or a future detection timestamp: treat as just
            // detected (FRESH) rather than guessing
            return FRESH;
        }
        if (age.compareTo(FRESH_LIMIT) < 0) {
            return FRESH;
        }
        if (age.compareTo(STALE_THRESHOLD) <= 0) {
            return AGING;
        }
        return STALE;
    }
}
