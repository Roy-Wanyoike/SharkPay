package com.sharkpay.payouts.ports;

/**
 * Randomness port: jitter for the retry backoff (and any future token
 * needs). Production uses a CSPRNG; tests use deterministic fakes so
 * backoff sequences are exact (ADR 003 §3 consumer-driven ports).
 */
public interface Randomness {

    /** A uniformly random {@code long} in [0, boundExclusive). */
    long bounded(long boundExclusive);

    /** Production adapter: {@link java.security.SecureRandom}-backed. */
    final class SecureRandomness implements Randomness {

        private final java.security.SecureRandom random = new java.security.SecureRandom();

        @Override
        public long bounded(long boundExclusive) {
            if (boundExclusive <= 0) {
                throw new IllegalArgumentException("bound must be positive: " + boundExclusive);
            }
            return random.nextLong(boundExclusive);
        }
    }
}
