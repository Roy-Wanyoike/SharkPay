package com.sharkpay.wallet.domain;

/**
 * Base class for wallet domain rule violations (mapped to typed HTTP errors
 * by the API layer; none of them is a 500).
 */
public abstract class WalletDomainException extends RuntimeException {

    protected WalletDomainException(String message) {
        super(message);
    }

    protected WalletDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
