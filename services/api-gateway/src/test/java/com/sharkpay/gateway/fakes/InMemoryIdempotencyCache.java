package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.ports.IdempotencyCache;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link IdempotencyCache} fake (scope, key) → cached response.
 */
public final class InMemoryIdempotencyCache implements IdempotencyCache {

    private final Map<String, CachedResponse> entries = new HashMap<>();

    @Override
    public Optional<CachedResponse> find(String scope, String idempotencyKey) {
        return Optional.ofNullable(entries.get(key(scope, idempotencyKey)));
    }

    @Override
    public void put(String scope, String idempotencyKey, CachedResponse response) {
        entries.put(key(scope, idempotencyKey), response);
    }

    private static String key(String scope, String idempotencyKey) {
        return scope + "|" + idempotencyKey;
    }

    /** Test oracle. */
    public Map<String, CachedResponse> all() {
        return Map.copyOf(entries);
    }
}
