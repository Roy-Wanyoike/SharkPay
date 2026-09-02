package com.sharkpay.payouts.ports;

import java.util.Objects;
import java.util.Optional;

/**
 * Idempotency store for the mutating operations of this service (create
 * transfer, create payout, cancel payout, provider-result ingestion). Keys
 * are scoped by operation type — the same raw header value used for two
 * different operations never collides — and bound to the canonical
 * fingerprint of the request they first served, mirroring the wallet
 * service's store.
 */
public interface IdempotencyStore {

    Optional<StoredRequest> find(Scope scope, String idempotencyKey);

    /** Stores the record for the scoped key (the key must not be present yet). */
    void put(Scope scope, String idempotencyKey, StoredRequest request);

    /** Releases a reservation made for a request that ultimately failed. */
    void remove(Scope scope, String idempotencyKey);

    /** Operation scopes (the idempotency_keys.scope CHECK constraint). */
    enum Scope {
        CREATE_TRANSFER, CREATE_PAYOUT, CANCEL_PAYOUT, PROVIDER_RESULT
    }

    /**
     * A served request: the fingerprint it was served under and the id of
     * the entity it drove. A replay with the same fingerprint returns the
     * original entity (no double effect); a different fingerprint is client
     * misuse (409 idempotency_conflict).
     */
    record StoredRequest(String requestFingerprint, String entityId) {

        public StoredRequest {
            if (requestFingerprint == null || requestFingerprint.isBlank()) {
                throw new IllegalArgumentException("requestFingerprint is required");
            }
            if (entityId == null || entityId.isBlank()) {
                throw new IllegalArgumentException("entityId is required");
            }
            Objects.requireNonNull(entityId, "entityId is required");
        }
    }
}
