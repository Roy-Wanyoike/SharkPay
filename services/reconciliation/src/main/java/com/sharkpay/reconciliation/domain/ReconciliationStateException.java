package com.sharkpay.reconciliation.domain;

/**
 * An illegal state transition (break lifecycle, run lifecycle, or
 * compensation lifecycle). Surfaces as 409 {@code state_conflict} — the
 * resource exists but the requested change contradicts its current state.
 */
public class ReconciliationStateException extends ReconciliationException {

    public ReconciliationStateException(String message) {
        super(message);
    }
}
