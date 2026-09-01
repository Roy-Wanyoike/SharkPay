package com.sharkpay.risk.events;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID v7 generator (timestamp-ordered, as required by the event contract's
 * id description: "Globally unique event id (UUID v7)").
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    /** New id for the current wall-clock time. */
    public static UUID next() {
        return fromMillis(System.currentTimeMillis());
    }

    /** Deterministic constructor for a given epoch-millisecond (tests). */
    public static UUID fromMillis(long epochMillis) {
        long randA = RANDOM.nextInt() & 0x0FFFL;
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randA;
        long lsb = 0x8000_0000_0000_0000L | randB;
        return new UUID(msb, lsb);
    }
}
