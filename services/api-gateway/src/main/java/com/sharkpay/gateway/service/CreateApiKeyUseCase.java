package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.ports.ApiKeyRepository;
import com.sharkpay.gateway.ports.Randomness;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Create-api-key use-case: generates the plaintext secret from the
 * {@link Randomness} port, persists only its SHA-256 hash, and returns the
 * plaintext exactly once (in the creation response — never again, not even
 * on idempotent replays; docs/SECURITY.md §2, BACKEND-DESIGN.md §10).
 */
public final class CreateApiKeyUseCase {

    /** Default per-key quotas (docs/API-CONTRACTS.md §6 read-class burst). */
    public static final int DEFAULT_RPM_LIMIT = 300;
    public static final long DEFAULT_MONTHLY_LIMIT = 2_000_000L;

    private final ApiKeyRepository keys;
    private final Randomness randomness;
    private final Clock clock;

    public CreateApiKeyUseCase(ApiKeyRepository keys, Randomness randomness, Clock clock) {
        this.keys = Objects.requireNonNull(keys, "apiKeyRepository is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param principalId  owning principal (keys are listed/used under this identity)
     * @param scopes       fail-closed scope subset (at least one)
     * @param rpmLimit     requests-per-minute quota (≤ 0 → default)
     * @param monthlyLimit requests-per-month quota (≤ 0 → default)
     * @return the stored key plus the plaintext secret — the only time it exists
     */
    public Result create(UUID principalId, Set<Scope> scopes, Integer rpmLimit,
                         Long monthlyLimit) {
        Objects.requireNonNull(principalId, "principalId is required");
        Objects.requireNonNull(scopes, "scopes are required");
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("an api key requires at least one scope");
        }
        int rpm = rpmLimit == null || rpmLimit <= 0 ? DEFAULT_RPM_LIMIT : rpmLimit;
        long monthly = monthlyLimit == null || monthlyLimit <= 0 ? DEFAULT_MONTHLY_LIMIT
                : monthlyLimit;

        String plaintext = randomness.apiKeySecret();
        String secretHash = KeyHasher.sha256Hex(plaintext);
        ApiKey key = ApiKey.active(randomness.apiKeyId(), principalId, secretHash,
                Set.copyOf(scopes), rpm, monthly, clock.instant());
        keys.save(key);
        return new Result(key, plaintext);
    }

    /** @param key        the persisted key (hash only)
     *  @param plaintext  the secret, existing only in this result */
    public record Result(ApiKey key, String plaintext) {
    }
}
