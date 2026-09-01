package com.sharkpay.payments.api.dto;

import com.sharkpay.payments.domain.PaymentIntent;

import java.time.Instant;

/**
 * The payment intent resource (payments.yaml Payment). Optional fields
 * ({@code metadata}, {@code failure_reason}, {@code provider_ref},
 * {@code updated_at}) are omitted when null (NON_NULL serialization);
 * {@code next_action.type} is always {@code "none"} in /v1.
 */
public record PaymentJson(String id, String state, MoneyJson amount, MoneyJson fee,
                          String destination_wallet, String rail, java.util.Map<String, String> metadata,
                          NextAction next_action, String failure_reason, String provider_ref,
                          Instant expires_at, Instant created_at, Instant updated_at) {

    public record NextAction(String type) {

        public static NextAction none() {
            return new NextAction("none");
        }
    }

    public static PaymentJson of(PaymentIntent intent) {
        return new PaymentJson(intent.id(), intent.state().wireName(),
                MoneyJson.of(intent.amount()), MoneyJson.of(intent.fee()),
                intent.destination().internalWalletId().orElse(null),
                intent.rail().wireName(),
                intent.metadata().isEmpty() ? null : intent.metadata(),
                NextAction.none(),
                intent.failureReason(),
                intent.providerRef(),
                intent.expiresAt(),
                intent.createdAt(),
                intent.updatedAt());
    }
}
