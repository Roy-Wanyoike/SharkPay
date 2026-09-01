package com.sharkpay.identity.fakes;

import com.sharkpay.identity.ports.IdempotentRequest;
import com.sharkpay.identity.ports.IdempotencyStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyStore} fake.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotentRequest> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotentRequest> findByKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    @Override
    public void save(IdempotentRequest request) {
        byKey.put(request.key(), request);
    }

    public int count() {
        return byKey.size();
    }
}
