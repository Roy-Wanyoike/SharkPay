package com.sharkpay.fx.ports;

/**
 * Idempotency record: a client Idempotency-Key mapped to the canonical
 * fingerprint of the request it first served and the conversion it created.
 *
 * @param requestFingerprint canonical fingerprint of the original request
 *                           body (a mismatch on replay is a 409
 *                           {@code idempotency_conflict})
 * @param conversionId       id of the conversion created under the key
 */
public record StoredRequest(String requestFingerprint, String conversionId) {

    public StoredRequest {
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("requestFingerprint is required");
        }
        if (conversionId == null || conversionId.isBlank()) {
            throw new IllegalArgumentException("conversionId is required");
        }
    }
}
