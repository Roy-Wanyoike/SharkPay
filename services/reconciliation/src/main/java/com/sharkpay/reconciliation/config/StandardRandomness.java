package com.sharkpay.reconciliation.config;

import com.sharkpay.reconciliation.ports.Randomness;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Production randomness: UUID v7 for event ids and source refs (48-bit
 * epoch ms + random, RFC 9562), 32-hex public ids (the same shapes the
 * wallet/fx/payouts services use).
 */
public final class StandardRandomness implements Randomness {

    @Override
    public UUID uuidV7() {
        long timestamp = System.currentTimeMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (timestamp << 16) | 0x7000L | random.nextInt(0x1000);
        long lsb = 0x8000000000000000L | (random.nextLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }

    @Override
    public String runId() {
        return "run_" + hex();
    }

    @Override
    public String breakId() {
        return "brk_" + hex();
    }

    @Override
    public String compensationId() {
        return "cmp_" + hex();
    }

    @Override
    public String settlementId() {
        return "str_" + hex();
    }

    private static String hex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
