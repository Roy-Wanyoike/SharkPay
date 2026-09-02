package com.sharkpay.reconciliation.domain;

/**
 * Base of the reconciliation domain's checked-by-contract exception
 * hierarchy (mirrors wallet's {@code WalletDomainException}). Subclasses
 * carry the HTTP mapping documented on each class.
 */
public abstract class ReconciliationException extends RuntimeException {

    protected ReconciliationException(String message) {
        super(message);
    }

    protected ReconciliationException(String message, Throwable cause) {
        super(message, cause);
    }
}
