package com.sharkpay.payments.domain;

/**
 * A reversal amount exceeds the captured amount (STATE-MACHINES.md §1 guard:
 * "reversal amount ≤ captured amount"). Maps to 422.
 */
public class ReversalExceedsCapturedException extends PaymentDomainException {

    private final String paymentId;

    public ReversalExceedsCapturedException(String paymentId) {
        super("reversal amount exceeds the captured amount of payment " + paymentId);
        this.paymentId = paymentId;
    }

    public String paymentId() {
        return paymentId;
    }
}
