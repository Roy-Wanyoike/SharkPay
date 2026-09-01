package com.sharkpay.identity.domain.exception;

/**
 * A request failed input validation. Maps to HTTP 400.
 * Carries a stable machine-readable {@code code} used in the error body.
 */
public class ValidationException extends RuntimeException {

    private final String code;

    public ValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
