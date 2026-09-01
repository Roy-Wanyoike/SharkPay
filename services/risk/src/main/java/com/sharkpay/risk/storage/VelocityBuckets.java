package com.sharkpay.risk.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-width time buckets behind the velocity_counters table. The store
 * keeps one row per (subject, minute bucket, currency); a sliding window of
 * any duration is the sum of the buckets it covers.
 *
 * <p>Approximation (documented): bucket sums are inclusive of the boundary
 * bucket, so a window is measured to at most one bucket (1 minute) more
 * than requested — the error is on the strict/safe side (may deny slightly
 * earlier, never later). Configured windows are &gt;= 1 hour in practice.</p>
 */
public final class VelocityBuckets {

    public static final long BUCKET_SECONDS = 60;

    private VelocityBuckets() {
    }

    /** Bucket id of an instant: {@code m<epochMinute>}. */
    public static String bucketId(Instant at) {
        return "m" + bucketIndex(at);
    }

    /** Epoch-minute index of an instant. */
    public static long bucketIndex(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), BUCKET_SECONDS);
    }

    /**
     * Bucket ids covering the sliding window ending at {@code now} (window
     * start inclusive). At least one bucket; ordered ascending; distinct.
     */
    public static List<String> windowBucketIds(Duration window, Instant now) {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be a positive duration");
        }
        long last = bucketIndex(now);
        long windowSeconds = Math.max(1, window.toSeconds());
        long first = Math.floorDiv(now.getEpochSecond() - windowSeconds + 1, BUCKET_SECONDS);
        if (first > last) {
            first = last;
        }
        List<String> ids = new ArrayList<>((int) (last - first + 1));
        for (long index = first; index <= last; index++) {
            ids.add("m" + index);
        }
        return ids;
    }
}
