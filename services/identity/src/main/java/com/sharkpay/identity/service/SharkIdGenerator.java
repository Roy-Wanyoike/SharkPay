package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.ports.PrincipalRepository;
import com.sharkpay.identity.ports.Randomness;

/**
 * Generates unique {@link SharkId}s: 6 random Crockford base32 characters
 * plus the 2-character mod-97 check pair (see {@link SharkId} for the
 * algorithm spec). Retries on SharkId collisions, bounded by
 * {@link #MAX_ATTEMPTS}.
 */
public final class SharkIdGenerator {

    public static final int MAX_ATTEMPTS = 10;

    private final PrincipalRepository principalRepository;
    private final Randomness randomness;

    public SharkIdGenerator(PrincipalRepository principalRepository, Randomness randomness) {
        this.principalRepository = principalRepository;
        this.randomness = randomness;
    }

    /**
     * @return a fresh SharkId not present in the principal repository.
     * @throws ConflictException SHARK_ID_GENERATION_EXHAUSTED when every
     *         attempt collided (practically impossible with 30 random bits
     *         and bounded retries).
     */
    public SharkId generate() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            SharkId candidate = randomCandidate();
            if (principalRepository.findBySharkId(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new ConflictException("SHARK_ID_GENERATION_EXHAUSTED",
                "failed to generate a unique SharkId after " + MAX_ATTEMPTS + " attempts");
    }

    private SharkId randomCandidate() {
        StringBuilder data = new StringBuilder(SharkId.DATA_CHARS);
        for (int i = 0; i < SharkId.DATA_CHARS; i++) {
            data.append(SharkId.ALPHABET.charAt(randomness.nextInt(SharkId.ALPHABET.length())));
        }
        return SharkId.fromData(data.toString());
    }
}
