package com.sharkpay.payments.domain;

/**
 * Base class of the payments domain's checked-invariant failures. Subclasses
 * map to specific HTTP responses in the API layer; the base itself is an
 * unexpected internal error.
 */
public class PaymentDomainException extends RuntimeException {

    public PaymentDomainException(String message) {
        super(message);
    }

    public PaymentDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
