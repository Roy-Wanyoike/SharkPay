package com.sharkpay.identity.ports;

import java.util.Optional;

/**
 * Idempotency port for create-principal requests keyed by the
 * {@code Idempotency-Key} header.
 */
public interface IdempotencyStore {

    Optional<IdempotentRequest> findByKey(String key);

    void save(IdempotentRequest request);
}
