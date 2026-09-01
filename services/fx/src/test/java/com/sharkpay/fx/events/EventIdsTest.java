package com.sharkpay.fx.events;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event ids are UUID v7 (RFC 9562: 48-bit epoch millis + random): the
 * catalog convention consumers dedupe on. Version/variant bits pinned,
 * monotonically non-decreasing timestamps, globally unique.
 */
class EventIdsTest {

    @Test
    void generatesUuidV7WithPinnedVersionAndVariantBits() {
        for (int i = 0; i < 100; i++) {
            String id = EventIds.uuidV7().toString();
            assertThat(id).matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
    }

    @Test
    void idsAreUniqueAndCarryTheCurrentEpochMillis() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            java.util.UUID id = EventIds.uuidV7();
            assertThat(seen.add(id.toString())).isTrue();
        }
        // the embedded 48-bit timestamp is the current epoch in milliseconds
        long now = System.currentTimeMillis();
        long embedded = EventIds.uuidV7().getMostSignificantBits() >>> 16;
        assertThat(Math.abs(now - embedded)).isLessThan(5_000);
    }
}
