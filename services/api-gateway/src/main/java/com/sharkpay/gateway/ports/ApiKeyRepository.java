package com.sharkpay.gateway.ports;

import com.sharkpay.gateway.domain.ApiKey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API key storage port. Lookups by hash serve authentication (SHA-256
 * first, then a constant-time digest comparison — see
 * {@code KeyHasher}); listing is always scoped to the owning principal.
 */
public interface ApiKeyRepository {

    ApiKey save(ApiKey key);

    Optional<ApiKey> findById(String id);

    /** The key whose stored hash equals the given SHA-256 hex digest. */
    Optional<ApiKey> findByHash(String secretHash);

    /** All keys of the principal, id-ordered, cursor-paginated. */
    List<ApiKey> listByPrincipal(UUID principalId, int limit, String cursor);
}
