package com.sharkpay.identity.domain.exception;

/**
 * A referenced aggregate does not exist. Maps to HTTP 404.
 */
public class NotFoundException extends RuntimeException {

    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
