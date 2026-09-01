package com.sharkpay.payments.fakes;

import com.sharkpay.payments.ports.Randomness;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic {@link Randomness} fake: sequential payment ids
 * ({@code pay_000…1}), sequential UUID v7 event ids and sequential request
 * ids — replayable unit tests (the domain never touches system entropy
 * directly, ADR 003 §3).
 */
public final class SequentialRandomness implements Randomness {

    private final Instant epoch;
    private final AtomicLong counter = new AtomicLong();

    public SequentialRandomness() {
        this(Instant.parse("2026-09-01T00:00:00Z"));
    }

    public SequentialRandomness(Instant epoch) {
        this.epoch = epoch;
    }

    @Override
    public UUID uuidV7() {
        long sequence = counter.incrementAndGet();
        long timestamp = epoch.toEpochMilli() + sequence;
        // version 7 (0x7000) + sequence in the ver_a/ver_b random bits;
        // variant 10xx forces the RFC 9562 layout
        long msb = (timestamp << 16) | 0x7000L | (sequence & 0x0FFFL);
        long lsb = 0x8000000000000000L | sequence;
        return new UUID(msb, lsb);
    }

    @Override
    public String paymentId() {
        return "pay_" + String.format("%022d", counter.incrementAndGet());
    }

    @Override
    public String requestId() {
        return "req_" + String.format("%012d", counter.incrementAndGet());
    }

    /** Values handed out so far (diagnostics). */
    public long handedOut() {
        return counter.get();
    }
}
