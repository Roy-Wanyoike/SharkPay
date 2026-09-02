package com.sharkpay.gateway.api.dto;

import com.sharkpay.gateway.service.SandboxPaymentService.SandboxPayment;

import java.time.Instant;

/** Sandbox payment JSON (the /sandbox simulated provider view). */
public record SandboxPaymentJson(String id, String state, long amount_minor, String currency,
                                 int exponent, String destination_wallet, String rail,
                                 Instant created_at) {

    public static SandboxPaymentJson of(SandboxPayment payment) {
        return new SandboxPaymentJson(payment.id(), payment.state(), payment.amountMinor(),
                payment.currency(), payment.exponent(), payment.destinationWallet(),
                payment.rail(), payment.createdAt());
    }
}
