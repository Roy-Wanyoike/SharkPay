package com.sharkpay.risk.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VelocityBucketsTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:30:45Z");

    @Test
    void bucketIdIsTheEpochMinuteIndex() {
        long expectedIndex = Math.floorDiv(NOW.getEpochSecond(), VelocityBuckets.BUCKET_SECONDS);

        assertThat(VelocityBuckets.bucketIndex(NOW)).isEqualTo(expectedIndex);
        assertThat(VelocityBuckets.bucketId(NOW)).isEqualTo("m" + expectedIndex);
        assertThat(VelocityBuckets.BUCKET_SECONDS).isEqualTo(60);
    }

    @Test
    void bucketIndexFloorsAcrossMinuteBoundaries() {
        Instant at59 = Instant.parse("2026-09-01T10:00:59Z");
        Instant at00 = Instant.parse("2026-09-01T10:01:00Z");

        assertThat(VelocityBuckets.bucketIndex(at59))
                .isEqualTo(VelocityBuckets.bucketIndex(at00.minusSeconds(1)));
        assertThat(VelocityBuckets.bucketIndex(at00))
                .isEqualTo(VelocityBuckets.bucketIndex(at59) + 1);
    }

    @Test
    void oneHourWindowCoversSixtyOneInclusiveBuckets() {
        List<String> ids = VelocityBuckets.windowBucketIds(Duration.ofHours(1), NOW);

        long last = VelocityBuckets.bucketIndex(NOW);
        assertThat(ids).hasSize(61);
        assertThat(ids).isSorted();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids.get(0)).isEqualTo("m" + (last - 60));
        assertThat(ids.get(ids.size() - 1)).isEqualTo("m" + last);
    }

    @Test
    void subMinuteWindowsCollapseToTheCurrentBucket() {
        List<String> ids = VelocityBuckets.windowBucketIds(Duration.ofSeconds(30), NOW);

        assertThat(ids).containsExactly(VelocityBuckets.bucketId(NOW));
    }

    @Test
    void windowsShorterThanABucketIncludeOneExtraBoundaryBucket() {
        // 59s window starting inside the previous minute still covers that bucket
        List<String> ids = VelocityBuckets.windowBucketIds(Duration.ofSeconds(59), NOW);

        assertThat(ids).hasSize(2);
        assertThat(ids).isSorted();
    }

    @Test
    void zeroNegativeAndNullWindowsAreRejected() {
        assertThatThrownBy(() -> VelocityBuckets.windowBucketIds(Duration.ZERO, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> VelocityBuckets.windowBucketIds(Duration.ofSeconds(-1), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> VelocityBuckets.windowBucketIds(null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
