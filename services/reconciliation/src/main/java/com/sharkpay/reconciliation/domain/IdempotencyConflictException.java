package com.sharkpay.reconciliation.domain;

/**
 * An Idempotency-Key was replayed with a different request payload (the key
 * is bound to the canonical fingerprint of the request it first served).
 * Client misuse → 409 {@code idempotency_conflict}.
 */
public class IdempotencyConflictException extends ReconciliationException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency key " + idempotencyKey + " was already used with a different request");
    }
}
