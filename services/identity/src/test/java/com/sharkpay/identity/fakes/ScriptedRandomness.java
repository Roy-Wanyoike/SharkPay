package com.sharkpay.identity.fakes;

import com.sharkpay.identity.ports.Randomness;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scripted randomness: values come from the script queue first, then a
 * deterministic SplitMix64 stream (distinct draw sequences once the script
 * is exhausted, so generated ids stay unique within a test).
 * {@link #lockTo(int)} pins every draw to one value — used to
 * force SharkId collisions and exhaustion.
 */
public final class ScriptedRandomness implements Randomness {

    private final Deque<Integer> script = new ArrayDeque<>();
    private Integer locked;
    private long counter;

    public ScriptedRandomness then(int value) {
        script.addLast(value);
        return this;
    }

    public ScriptedRandomness repeat(int value, int times) {
        for (int i = 0; i < times; i++) {
            then(value);
        }
        return this;
    }

    public ScriptedRandomness lockTo(int value) {
        this.locked = value;
        return this;
    }

    @Override
    public int nextInt(int bound) {
        if (locked != null) {
            return Math.floorMod(locked, bound);
        }
        Integer next = script.pollFirst();
        if (next != null) {
            return Math.floorMod(next, bound);
        }
        counter += 1;
        // SplitMix64 strong mixing of the counter: consecutive counters map to
        // avalanche-independent values, so 6-char candidate sequences stay
        // unique across any realistic test length (verified: 100 candidates
        // all distinct).
        long z = counter + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EFL;
        z = z ^ (z >>> 31);
        return Math.floorMod((int) (z >>> 32), bound);
    }
}
