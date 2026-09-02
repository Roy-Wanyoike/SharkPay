package com.sharkpay.payouts.api.dto;

import com.sharkpay.payouts.domain.Transfer;

import java.util.Map;

/**
 * The transfer resource (contracts/openapi/v1/transfers.yaml Transfer).
 * Optional fields (entry_id, failure_reason, metadata) are omitted when
 * null (NON_NULL inclusion).
 */
public record TransferJson(String id, String state, String source_wallet, String destination_wallet,
                           MoneyJson amount, MoneyJson fee, String entry_id, String failure_reason,
                           Map<String, String> metadata, String created_at) {

    public static TransferJson of(Transfer transfer) {
        return new TransferJson(transfer.id(), transfer.state().wireName(),
                transfer.sourceWalletId(), transfer.destinationWalletId(),
                MoneyJson.of(transfer.amount()), MoneyJson.of(transfer.fee()),
                transfer.entryId() == null ? null : transfer.entryId().toString(),
                transfer.failureReason(), transfer.metadata().isEmpty() ? null : transfer.metadata(),
                transfer.createdAt().toString());
    }
}
