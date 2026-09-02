package com.sharkpay.payouts.domain;

/**
 * Base of every payouts-domain error. Subclasses map onto the canonical
 * error envelope (contracts/openapi/v1/common.yaml): state/idempotency
 * conflicts → 409, business rejections → 422, unknown entities → 404.
 * Anything else surfacing as this base type is an internal error (500) and
 * must never leave money half-moved.
 */
public class PayoutsDomainException extends RuntimeException {

    public PayoutsDomainException(String message) {
        super(message);
    }

    public PayoutsDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
