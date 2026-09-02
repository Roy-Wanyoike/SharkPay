package com.sharkpay.payouts.fakes;

import com.sharkpay.payouts.ports.Randomness;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic {@link Randomness} fake: {@link #bounded(long)} hands out
 * the scripted sequence (default 0 = no jitter), so backoff sequences are
 * exact and replayable (ADR 003 §3 — the domain never touches system
 * entropy directly).
 */
public final class ScriptedRandomness implements Randomness {

    private final Queue<Long> script = new ConcurrentLinkedQueue<>();
    private final AtomicLong fallback = new AtomicLong();
    private final AtomicLong draws = new AtomicLong();

    /** Queues the next values handed out by {@link #bounded(long)}. */
    public ScriptedRandomness script(long... values) {
        for (long value : values) {
            script.add(value);
        }
        return this;
    }

    /** The fixed value handed out once the script is exhausted (default 0). */
    public ScriptedRandomness fallback(long value) {
        fallback.set(value);
        return this;
    }

    @Override
    public long bounded(long boundExclusive) {
        if (boundExclusive <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + boundExclusive);
        }
        draws.incrementAndGet();
        Long scripted = script.poll();
        long value = scripted == null ? fallback.get() : scripted;
        if (value < 0 || value >= boundExclusive) {
            throw new IllegalStateException("scripted value " + value
                    + " is outside the requested bound [0, " + boundExclusive + ")");
        }
        return value;
    }

    /** Number of draws so far (diagnostics). */
    public long draws() {
        return draws.get();
    }
}
