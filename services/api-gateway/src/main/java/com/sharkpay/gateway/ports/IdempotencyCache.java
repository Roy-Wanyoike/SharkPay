package com.sharkpay.gateway.ports;

import java.util.Optional;

/**
 * Idempotency cache for the /v1 passthrough and the gateway's own
 * state-changing POSTs: keyed by (scope, idempotency key) where the scope
 * binds the key to a route class (mission: "cache (scope: key+route)").
 *
 * <p>On a replay the stored response is returned with
 * {@code X-Idempotent-Replay: true}; a different request fingerprint under
 * the same key is a 409 {@code idempotency_conflict}
 * (contracts/openapi/v1/common.yaml Idempotency-Key semantics).</p>
 *
 * <p>Two stored shapes share one table: passthrough entries carry the
 * upstream (status, body) verbatim; native management entries carry the
 * created entity id instead — never a response body, so plaintext secrets
 * can never end up at rest here (hash-never-plaintext).</p>
 */
public interface IdempotencyCache {

    /**
     * Finds the record stored under the scoped key.
     *
     * @param scope          cache scope (e.g. {@code PASSTHROUGH:PAYMENTS}, {@code CREATE_API_KEY})
     * @param idempotencyKey raw Idempotency-Key header value
     */
    Optional<CachedResponse> find(String scope, String idempotencyKey);

    /** Stores the record served under the scoped key (absent keys only). */
    void put(String scope, String idempotencyKey, CachedResponse response);

    /**
     * A served request: the fingerprint it was served under, plus either the
     * relayed upstream response (passthrough) or the id of the entity it
     * created (native management endpoints — no key material at rest).
     */
    record CachedResponse(String requestFingerprint, int status, String body, String entityId) {

        public CachedResponse {
            if (requestFingerprint == null || requestFingerprint.isBlank()) {
                throw new IllegalArgumentException("requestFingerprint is required");
            }
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("status must be a valid HTTP code");
            }
        }

        /** A passthrough entry: the upstream response verbatim. */
        public static CachedResponse upstream(String fingerprint, int status, String body) {
            return new CachedResponse(fingerprint, status, body, null);
        }

        /** A native management entry: the created entity id, no body. */
        public static CachedResponse entity(String fingerprint, int status, String entityId) {
            return new CachedResponse(fingerprint, status, null, entityId);
        }
    }
}
