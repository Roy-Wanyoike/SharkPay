package com.sharkpay.fx.events;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UUID v7 generator for event ids: 48-bit unix epoch milliseconds + random
 * bits (RFC 9562). Time-ordered ids match the event catalog convention
 * (contracts/events/events.md: {@code "id": "<uuid v7>"} — consumers dedupe
 * on it).
 */
public final class EventIds {

    private EventIds() {
    }

    /** Fresh time-ordered UUID v7. */
    public static UUID uuidV7() {
        long timestamp = System.currentTimeMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (timestamp << 16) | 0x7000L | random.nextInt(0x1000);
        long lsb = 0x8000000000000000L | (random.nextLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }
}
