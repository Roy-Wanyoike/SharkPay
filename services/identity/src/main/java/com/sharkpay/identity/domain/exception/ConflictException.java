package com.sharkpay.identity.domain.exception;

/**
 * The request conflicts with the current state of an aggregate
 * (illegal transition, terminal state, duplicate key, idempotency conflict).
 * Maps to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
