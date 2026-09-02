package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.ApiKeyStateException;
import com.sharkpay.gateway.ports.ApiKeyRepository;
import com.sharkpay.gateway.ports.Randomness;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Rotate-api-key use-case: mints a fresh secret for the key's principal and
 * demotes the current secret to {@code ROTATING} with the 24 h grace window
 * — the old secret keeps working until grace expiry, then 401s exactly like
 * an unknown key (docs/SECURITY.md §2). Only active keys can rotate;
 * rotating a rotating or revoked key is a state conflict.
 */
public final class RotateApiKeyUseCase {

    private final ApiKeyRepository keys;
    private final Randomness randomness;
    private final Clock clock;

    public RotateApiKeyUseCase(ApiKeyRepository keys, Randomness randomness, Clock clock) {
        this.keys = Objects.requireNonNull(keys, "apiKeyRepository is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param keyId      the key whose secret is being replaced
     * @param principal  the caller (foreign keys are indistinguishable from missing ones)
     * @return the demoted old key, the new active key and the new plaintext
     */
    public Result rotate(String keyId, UUID principal) {
        ApiKey existing = owned(keyId, principal);
        if (existing.status() != com.sharkpay.gateway.domain.ApiKeyStatus.ACTIVE) {
            throw new ApiKeyStateException("key " + keyId + " is " + existing.status().wireName()
                    + " — only active keys can be rotated");
        }
        ApiKey demoted = keys.save(existing.markRotating(clock.instant()));

        String plaintext = randomness.apiKeySecret();
        ApiKey fresh = keys.save(ApiKey.active(randomness.apiKeyId(), principal,
                KeyHasher.sha256Hex(plaintext), existing.scopes(), existing.rpmLimit(),
                existing.monthlyLimit(), clock.instant()));
        return new Result(demoted, fresh, plaintext);
    }

    private ApiKey owned(String keyId, UUID principal) {
        return keys.findById(keyId)
                .filter(key -> key.principalId().equals(principal))
                .orElseThrow(() -> new NoSuchElementException("api key " + keyId + " not found"));
    }

    /** @param demoted the old key, valid until grace expiry
     *  @param fresh   the new active key (hash only)
     *  @param plaintext the new secret — exists only in this result */
    public record Result(ApiKey demoted, ApiKey fresh, String plaintext) {
    }
}
