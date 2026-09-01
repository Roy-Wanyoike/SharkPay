package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.ports.PaymentRepository;

import java.util.Objects;

/** Read a payment intent by id (payments.yaml getPayment; 404 unknown). */
public final class GetPaymentUseCase {

    private final PaymentRepository payments;

    public GetPaymentUseCase(PaymentRepository payments) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
    }

    public PaymentIntent get(String paymentId) {
        Objects.requireNonNull(paymentId, "paymentId is required");
        return payments.findById(paymentId)
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
    }
}
