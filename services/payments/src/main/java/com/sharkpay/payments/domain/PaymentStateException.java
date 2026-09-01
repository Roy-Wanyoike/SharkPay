package com.sharkpay.payments.domain;

/**
 * A transition outside the legal payment state machine
 * (docs/STATE-MACHINES.md §1: "any transition not listed there is a bug").
 * Maps to 409 {@code state_conflict}.
 */
public class PaymentStateException extends PaymentDomainException {

    private final String paymentId;
    private final PaymentState from;
    private final PaymentState attemptedTo;

    public PaymentStateException(String paymentId, PaymentState from, PaymentState attemptedTo) {
        super("payment " + paymentId + " is in state " + from.wireName()
                + " and cannot transition to " + attemptedTo.wireName());
        this.paymentId = paymentId;
        this.from = from;
        this.attemptedTo = attemptedTo;
    }

    public String paymentId() {
        return paymentId;
    }

    public PaymentState from() {
        return from;
    }

    public PaymentState attemptedTo() {
        return attemptedTo;
    }
}
