package com.sharkpay.identity.ports;

import java.util.Objects;
import java.util.UUID;

/**
 * A stored idempotent request: the key, a sha-256 fingerprint of the
 * canonical request body, and the principal that was created by it.
 */
public record IdempotentRequest(String key, String requestFingerprint, UUID principalId) {

    public IdempotentRequest {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
        Objects.requireNonNull(principalId, "principalId must not be null");
    }
}
