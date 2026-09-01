package com.sharkpay.fx.domain;

/**
 * Base type for FX domain rule violations. Subtypes are mapped to HTTP error
 * envelopes by the API layer; this type itself signals an unexpected domain
 * failure (mapped to 500).
 */
public class FxDomainException extends RuntimeException {

    public FxDomainException(String message) {
        super(message);
    }

    public FxDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
