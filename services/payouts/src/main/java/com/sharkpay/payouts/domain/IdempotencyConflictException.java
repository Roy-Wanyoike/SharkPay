package com.sharkpay.payouts.domain;

/** Idempotency-Key reused with a different request payload (409). */
public class IdempotencyConflictException extends PayoutsDomainException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key was already used with a different request payload.");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
