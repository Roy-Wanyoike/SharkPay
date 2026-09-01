package com.sharkpay.identity.config;

import com.sharkpay.identity.ports.Randomness;
import java.security.SecureRandom;

/**
 * Production {@link Randomness} adapter backed by {@link SecureRandom}.
 */
public final class SecureRandomness implements Randomness {

    private final SecureRandom random = new SecureRandom();

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
