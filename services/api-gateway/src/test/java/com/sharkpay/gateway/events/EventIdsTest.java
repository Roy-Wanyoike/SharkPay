package com.sharkpay.gateway.events;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event ids: UUID v7 (RFC 9562) — time-ordered, version 7, variant 10,
 * unique (events.md: "id: uuid v7", consumers dedupe on it).
 */
class EventIdsTest {

    @Test
    void idsAreUuidV7() {
        for (int i = 0; i < 100; i++) {
            UUID id = EventIds.uuidV7();
            assertEquals(7, id.version(), id + " must be version 7");
            assertEquals(2, id.variant(), id + " must be the RFC 4122 variant");
        }
    }

    @Test
    void idsAreUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(EventIds.uuidV7());
        }
        assertEquals(1_000, ids.size());
    }

    @Test
    void idsAreTimeOrderedAcrossMilliseconds() throws Exception {
        UUID first = EventIds.uuidV7();
        Thread.sleep(3);
        UUID later = EventIds.uuidV7();
        // v7 sorts on the 48-bit unix-epoch-ms prefix (not the random tail,
        // so same-millisecond order is NOT guaranteed — only cross-ms is)
        long firstMs = first.getMostSignificantBits() >>> 16;
        long laterMs = later.getMostSignificantBits() >>> 16;
        assertTrue(firstMs < laterMs, firstMs + " < " + laterMs);
        // the timestamp is roughly "now"
        assertTrue(Math.abs(System.currentTimeMillis() - firstMs) < 5_000,
                "timestamp must be current unix ms");
    }

    @Test
    void distinctDrawsDiffer() {
        assertNotEquals(EventIds.uuidV7(), EventIds.uuidV7());
    }
}
