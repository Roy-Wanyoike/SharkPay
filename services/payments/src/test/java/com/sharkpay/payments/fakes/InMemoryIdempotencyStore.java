package com.sharkpay.payments.fakes;

import com.sharkpay.payments.ports.IdempotencyStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency store (in-tree test fake, src/test, per ADR 003),
 * keyed by (scope, key) exactly like the {@code idempotency_keys} table.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Key(Scope scope, String idempotencyKey) {
    }

    private final Map<Key, StoredRequest> store = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredRequest> find(Scope scope, String idempotencyKey) {
        return Optional.ofNullable(store.get(new Key(scope, idempotencyKey)));
    }

    @Override
    public void put(Scope scope, String idempotencyKey, StoredRequest request) {
        store.put(new Key(scope, idempotencyKey), request);
    }

    @Override
    public void remove(Scope scope, String idempotencyKey) {
        store.remove(new Key(scope, idempotencyKey));
    }

    /** Number of stored keys (all scopes). */
    public int count() {
        return store.size();
    }

    /** Whether the scoped key is present. */
    public boolean contains(Scope scope, String idempotencyKey) {
        return store.containsKey(new Key(scope, idempotencyKey));
    }
}
