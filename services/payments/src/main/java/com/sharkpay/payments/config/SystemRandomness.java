package com.sharkpay.payments.config;

import com.sharkpay.payments.ports.Randomness;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Production {@link Randomness}: system entropy. UUID v7 (48-bit unix epoch
 * millis + random, RFC 9562) for event ids; prefixed UUID hex for payment /
 * request ids.
 */
public final class SystemRandomness implements Randomness {

    @Override
    public UUID uuidV7() {
        long timestamp = System.currentTimeMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (timestamp << 16) | 0x7000L | random.nextInt(0x1000);
        long lsb = 0x8000000000000000L | (random.nextLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }

    @Override
    public String paymentId() {
        return "pay_" + uuidV7().toString().replace("-", "");
    }

    @Override
    public String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
