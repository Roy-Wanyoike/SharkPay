package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.ApiKeyStatus;
import com.sharkpay.gateway.ports.ApiKeyRepository;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Revoke + list API keys. Revocation is immediate and idempotent: a revoked
 * secret 401s from the next request on; revoking an already-revoked key is
 * still a 204 for the owner (no state change). Listing never returns
 * secrets — hashes stay in storage.
 */
public final class ApiKeyAdminUseCase {

    private final ApiKeyRepository keys;
    private final Clock clock;

    public ApiKeyAdminUseCase(ApiKeyRepository keys, Clock clock) {
        this.keys = Objects.requireNonNull(keys, "apiKeyRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Revokes the caller's key (foreign keys look missing). */
    public void revoke(String keyId, UUID principal) {
        ApiKey key = keys.findById(keyId)
                .filter(candidate -> candidate.principalId().equals(principal))
                .orElseThrow(() -> new NoSuchElementException("api key " + keyId + " not found"));
        if (key.status() != ApiKeyStatus.REVOKED) {
            keys.save(key.revoked(clock.instant()));
        }
    }

    /** The caller's keys, id-ordered, cursor-paginated (no secrets). */
    public List<ApiKey> list(UUID principal, int limit, String cursor) {
        return keys.listByPrincipal(principal, limit, cursor);
    }
}
