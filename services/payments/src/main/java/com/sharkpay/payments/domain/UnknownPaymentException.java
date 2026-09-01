package com.sharkpay.payments.domain;

/**
 * A referenced payment intent does not exist. Maps to 404
 * {@code not_found} (common.yaml: "a referenced entity does not exist (path
 * id or request-body identifier)").
 */
public class UnknownPaymentException extends PaymentDomainException {

    private final String paymentId;

    public UnknownPaymentException(String paymentId) {
        super("Payment " + paymentId + " not found.");
        this.paymentId = paymentId;
    }

    public String paymentId() {
        return paymentId;
    }
}
