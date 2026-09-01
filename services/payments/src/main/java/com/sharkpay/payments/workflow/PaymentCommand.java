package com.sharkpay.payments.workflow;

/**
 * Workflow start argument: the payment intent to orchestrate. Kept to a
 * single field on purpose — everything else is read through activities so
 * the workflow code never touches money or mutable domain state directly
 * (determinism rule: workflow code only sequences, decides on booleans and
 * sleeps).
 */
public record PaymentCommand(String paymentId) {

    public PaymentCommand {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId is required");
        }
    }
}
