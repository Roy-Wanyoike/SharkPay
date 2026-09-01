package com.sharkpay.payments.workflow;

/**
 * Workflow result, returned once the intent reaches a terminal state (or a
 * terminal-for-this-run outcome). Plain strings — the workflow never
 * materialises domain objects.
 *
 * @param paymentId public intent id
 * @param state     final {@link com.sharkpay.payments.domain.PaymentState} wire name
 * @param reason    terminal reason (failure reason / null)
 */
public record PaymentOutcome(String paymentId, String state, String reason) {

    public PaymentOutcome {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state is required");
        }
    }
}
