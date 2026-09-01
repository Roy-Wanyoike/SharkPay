package com.sharkpay.wallet.ports;

import java.util.Optional;

/**
 * Idempotency store for the wallet service's mutating operations
 * (create-wallet, place-hold, release-hold, capture-hold). Keys are scoped
 * by operation type — the same raw header value used for two different
 * operations never collides — and bound to the canonical fingerprint of the
 * request they first served.
 */
public interface IdempotencyStore {

    Optional<StoredRequest> find(Scope scope, String idempotencyKey);

    /** Stores the record for the scoped key (the key must not be present yet). */
    void put(Scope scope, String idempotencyKey, StoredRequest request);

    /** Releases a reservation made for a request that ultimately failed. */
    void remove(Scope scope, String idempotencyKey);

    /** Operation scopes (the idempotency_keys.scope CHECK constraint). */
    enum Scope {
        CREATE_WALLET, PLACE_HOLD, RELEASE_HOLD, CAPTURE_HOLD
    }

    /**
     * A served request: the fingerprint it was served under and the id of the
     * entity it created. A replay with the same fingerprint returns the
     * original entity (no double effect); a different fingerprint is a
     * client misuse conflict.
     *
     * @param requestFingerprint canonical fingerprint of the original request
     * @param entityId           id of the wallet or hold created under the key
     */
    record StoredRequest(String requestFingerprint, String entityId) {

        public StoredRequest {
            if (requestFingerprint == null || requestFingerprint.isBlank()) {
                throw new IllegalArgumentException("requestFingerprint is required");
            }
            if (entityId == null || entityId.isBlank()) {
                throw new IllegalArgumentException("entityId is required");
            }
        }
    }
}
