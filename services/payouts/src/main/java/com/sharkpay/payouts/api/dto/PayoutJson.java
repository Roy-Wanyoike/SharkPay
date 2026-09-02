package com.sharkpay.payouts.api.dto;

import com.sharkpay.payouts.domain.Payout;

import java.util.Map;

/**
 * The payout resource (contracts/openapi/v1/payouts.yaml Payout). Optional
 * fields (metadata, failure_reason, return_reason, provider_ref,
 * expires_at, updated_at) are omitted when null (NON_NULL inclusion).
 */
public record PayoutJson(String id, String state, String source_wallet, MoneyJson amount,
                         MoneyJson fee, DestinationJson destination, String rail,
                         Map<String, String> metadata, String failure_reason, String return_reason,
                         String provider_ref, String expires_at, String created_at,
                         String updated_at) {

    public static PayoutJson of(Payout payout) {
        return new PayoutJson(payout.id(), payout.state().wireName(), payout.sourceWalletId(),
                MoneyJson.of(payout.amount()), MoneyJson.of(payout.fee()),
                DestinationJson.of(payout.destination()), payout.rail().wireName(),
                payout.metadata().isEmpty() ? null : payout.metadata(), payout.failureReason(),
                payout.returnReason(), payout.providerRef(), payout.expiresAt().toString(),
                payout.createdAt().toString(), payout.updatedAt().toString());
    }
}
