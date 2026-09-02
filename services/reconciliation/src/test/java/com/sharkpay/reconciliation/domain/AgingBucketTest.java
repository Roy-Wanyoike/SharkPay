package com.sharkpay.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RB-7 aging buckets, exact boundaries (mutable-clock driven elsewhere; the
 * pure function is pinned here): FRESH below 24 h, AGING in [24 h, 72 h],
 * STALE above 72 h — 24 h and 72 h sharp land in AGING.
 */
class AgingBucketTest {

    private static final Instant DETECTED = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void ageBelow24hIsFresh() {
        assertThat(AgingBucket.of(DETECTED, DETECTED.plus(Duration.ofHours(23)))).isEqualTo(AgingBucket.FRESH);
        assertThat(AgingBucket.of(DETECTED, DETECTED.plus(Duration.ofHours(23))
                .plus(Duration.ofSeconds(3600 - 1)))).isEqualTo(AgingBucket.FRESH);
        assertThat(AgingBucket.of(DETECTED, DETECTED)).isEqualTo(AgingBucket.FRESH);
    }

    @Test
    void exactly24hIsAgingNotFresh() {
        assertThat(AgingBucket.of(DETECTED, DETECTED.plus(AgingBucket.FRESH_LIMIT)))
                .isEqualTo(AgingBucket.AGING);
    }

    @Test
    void between24hAnd72hIsAging() {
        assertThat(AgingBucket.of(DETECTED, DETECTED.plus(Duration.ofHours(25))))
                .isEqualTo(AgingBucket.AGING);
        assertThat(AgingBucket.of(DETECTED, DETECTED.plus(Duration.ofHours(71))))
                .isEqualTo(AgingBucket.AGING);
    }

    @Test
    void exactly72hIsStillAging() {
        assertThat(AgingBucket.of(DETECTED, DETECTED.plus(AgingBucket.STALE_THRESHOLD)))
                .isEqualTo(AgingBucket.AGING);
    }

    @Test
    void above72hIsStale() {
        assertThat(AgingBucket.of(DETECTED, DETECTED.plus(AgingBucket.STALE_THRESHOLD)
                .plusNanos(1))).isEqualTo(AgingBucket.STALE);
        assertThat(AgingBucket.of(DETECTED, DETECTED.plus(Duration.ofHours(73))))
                .isEqualTo(AgingBucket.STALE);
    }

    @Test
    void aFutureDetectionTimestampIsFreshNeverGuessed() {
        // clock skew / future timestamp: not negative-bucket, not an error
        assertThat(AgingBucket.of(DETECTED, DETECTED.minusSeconds(600)))
                .isEqualTo(AgingBucket.FRESH);
    }

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> AgingBucket.of(null, DETECTED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AgingBucket.of(DETECTED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void wireNamesAndParsing() {
        assertThat(AgingBucket.FRESH.wireName()).isEqualTo("fresh");
        assertThat(AgingBucket.AGING.wireName()).isEqualTo("aging");
        assertThat(AgingBucket.STALE.wireName()).isEqualTo("stale");
        assertThat(AgingBucket.fromWireName("aging")).isEqualTo(AgingBucket.AGING);
        assertThatThrownBy(() -> AgingBucket.fromWireName("ancient"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown aging bucket");
    }
}
