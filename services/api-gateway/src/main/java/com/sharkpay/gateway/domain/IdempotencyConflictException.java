package com.sharkpay.gateway.domain;

/** Idempotency key reuse with a different request payload (409 idempotency_conflict). */
public final class IdempotencyConflictException extends GatewayDomainException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key was already used with a different request payload");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
