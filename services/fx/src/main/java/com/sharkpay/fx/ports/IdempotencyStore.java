package com.sharkpay.fx.ports;

import java.util.Optional;

/**
 * Idempotency store for convert requests (client Idempotency-Key scope:
 * (api key, endpoint, key) per contracts/openapi/v1/common.yaml — the API
 * gateway supplies the scoping; this store is keyed by the raw header value
 * as delivered by the integration layer).
 */
public interface IdempotencyStore {

    Optional<StoredRequest> find(String idempotencyKey);

    /** Stores the record for the key (the key must not be present yet). */
    void put(String idempotencyKey, StoredRequest request);

    /** Releases a reservation made for a request that ultimately failed. */
    void remove(String idempotencyKey);
}
