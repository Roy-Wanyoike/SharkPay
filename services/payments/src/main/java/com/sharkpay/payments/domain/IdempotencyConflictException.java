package com.sharkpay.payments.domain;

/**
 * An Idempotency-Key reused with a different request payload. Maps to 409
 * {@code idempotency_conflict} (common.yaml).
 */
public class IdempotencyConflictException extends PaymentDomainException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key was already used with a different request payload.");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
