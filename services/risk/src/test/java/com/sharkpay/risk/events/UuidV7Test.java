package com.sharkpay.risk.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void generatesVersion7IdsForCurrentWallClock() {
        UUID id = UuidV7.next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // RFC 4122 variant

        long timestamp = id.getMostSignificantBits() >>> 16;
        long now = Instant.now().toEpochMilli();
        assertThat(timestamp).isBetween(now - 300_000L, now + 300_000L);
    }

    @Test
    void deterministicConstructorEncodesTheGivenMillisecond() {
        long epochMillis = Instant.parse("2026-09-01T10:00:00Z").toEpochMilli();

        UUID id = UuidV7.fromMillis(epochMillis);

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(epochMillis);
    }

    @Test
    void successiveIdsDifferAndPreserveOrdering() {
        UUID first = UuidV7.fromMillis(1_000L);
        UUID second = UuidV7.fromMillis(2_000L);

        assertThat(first).isNotEqualTo(second);
        // timestamp-ordered: the later id compares greater (byte-order trick:
        // compare the timestamp half of the msb)
        assertThat(second.getMostSignificantBits() >>> 16)
                .isGreaterThan(first.getMostSignificantBits() >>> 16);
    }
}
