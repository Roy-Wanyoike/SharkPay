package com.sharkpay.fx.domain;

/**
 * An Idempotency-Key was reused with a different request payload (HTTP 409
 * {@code idempotency_conflict} per contracts/openapi/v1/common.yaml).
 */
public final class IdempotencyConflictException extends FxDomainException {

    public IdempotencyConflictException() {
        super("Idempotency-Key was already used with a different request payload");
    }
}
