package com.sharkpay.reconciliation.domain;

/**
 * A 4-eyes (two-person) control violation: SECURITY §4 / RB-7 step 3 —
 * manual compensation entries require a second, distinct person; the
 * requester can never approve their own compensation. Surfaces as 422
 * {@code four_eyes_violation}.
 */
public class FourEyesException extends ReconciliationException {

    public FourEyesException(String principal) {
        super("4-eyes violation: principal " + principal
                + " cannot approve a compensation they proposed (requester and approver must be distinct persons)");
    }
}
