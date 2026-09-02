package com.sharkpay.gateway.api.dto;

import com.sharkpay.gateway.domain.ApiKey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API key JSON. The {@code secret} field is present exactly once, in the
 * creation/rotation response — replays and listings never repeat it
 * (docs/SECURITY.md §2).
 */
public record ApiKeyJson(String id, UUID principal_id, List<String> scopes, String status,
                         String secret, Instant grace_expires_at, int rpm_limit,
                         long monthly_limit, Instant created_at, Instant updated_at) {

    /** The one rendering that carries the plaintext secret. */
    public static ApiKeyJson withSecret(ApiKey key, String plaintextSecret) {
        return of(key, plaintextSecret);
    }

    /** The redacted rendering (listings, replays, rotation views). */
    public static ApiKeyJson redacted(ApiKey key) {
        return of(key, null);
    }

    private static ApiKeyJson of(ApiKey key, String secret) {
        return new ApiKeyJson(key.id(), key.principalId(),
                key.scopes().stream().map(scope -> scope.wireName()).sorted().toList(),
                key.status().wireName(), secret,
                key.graceExpiresAtOptional().orElse(null), key.rpmLimit(), key.monthlyLimit(),
                key.createdAt(), key.updatedAt());
    }
}
