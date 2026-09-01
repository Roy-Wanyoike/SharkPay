package com.sharkpay.identity.ports;

/**
 * Randomness port. Injectable so tests can force deterministic ids and
 * SharkId collisions.
 */
@FunctionalInterface
public interface Randomness {

    /** @return a uniformly distributed value in [0, bound). */
    int nextInt(int bound);
}
