package com.sharkpay.reconciliation.fakes;

import com.sharkpay.reconciliation.ports.Randomness;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic randomness (in-tree test fake, ADR 003 §3): a monotone
 * counter drives every id, so tests can predict the exact ids and assert
 * uniqueness. UUIDs keep the v7 shape (version 7, RFC 4122 variant).
 */
public final class SequentialRandomness implements Randomness {

    private final AtomicLong seq = new AtomicLong();

    @Override
    public UUID uuidV7() {
        return UUID.fromString("00000000-0000-7000-8000-"
                + String.format("%012x", seq.incrementAndGet()));
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

    private String hex() {
        return String.format("%032x", seq.incrementAndGet());
    }
}
