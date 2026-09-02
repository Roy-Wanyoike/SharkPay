package com.sharkpay.reconciliation.domain;

/**
 * An illegal recon-window (missing end, or {@code from} not before
 * {@code to}). Surfaces as 400 {@code validation_error} — a malformed
 * request, not a business rejection.
 */
public class InvalidWindowException extends ReconciliationException {

    public InvalidWindowException(String message) {
        super(message);
    }
}
