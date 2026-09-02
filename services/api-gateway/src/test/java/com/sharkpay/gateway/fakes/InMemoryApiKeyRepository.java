package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.ports.ApiKeyRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ApiKeyRepository} fake (ADR 003 §3: the fake doubles as
 * the executable spec of the contract the real JPA adapter must satisfy).
 */
public final class InMemoryApiKeyRepository implements ApiKeyRepository {

    private final Map<String, ApiKey> keys = new ConcurrentHashMap<>();

    @Override
    public ApiKey save(ApiKey key) {
        keys.put(key.id(), key);
        return key;
    }

    @Override
    public Optional<ApiKey> findById(String id) {
        return Optional.ofNullable(keys.get(id));
    }

    @Override
    public Optional<ApiKey> findByHash(String secretHash) {
        return keys.values().stream()
                .filter(key -> key.secretHash().equals(secretHash))
                .findFirst();
    }

    @Override
    public List<ApiKey> listByPrincipal(UUID principalId, int limit, String cursor) {
        return keys.values().stream()
                .filter(key -> key.principalId().equals(principalId))
                .sorted(Comparator.comparing(ApiKey::id))
                .filter(key -> cursor == null || key.id().compareTo(cursor) > 0)
                .limit(Math.max(0, limit))
                .toList();
    }

    /** Test oracle: everything ever persisted (for hash-never-plaintext audits). */
    public Map<String, ApiKey> all() {
        return Map.copyOf(keys);
    }

    /** Test hook: removes a key row (vanishing-entity replay paths). */
    public void delete(String id) {
        keys.remove(id);
    }
}
