package com.sharkpay.fx.fakes;

import com.sharkpay.fx.ports.IdempotencyStore;
import com.sharkpay.fx.ports.StoredRequest;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory idempotency store (fake for tests and local dev wiring). */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, StoredRequest> store = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredRequest> find(String idempotencyKey) {
        return Optional.ofNullable(store.get(idempotencyKey));
    }

    @Override
    public void put(String idempotencyKey, StoredRequest request) {
        store.put(idempotencyKey, request);
    }

    @Override
    public void remove(String idempotencyKey) {
        store.remove(idempotencyKey);
    }
}
