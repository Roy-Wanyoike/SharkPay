package com.sharkpay.wallet.api.dto;

import com.sharkpay.wallet.domain.Wallet;

import java.time.Instant;
import java.util.UUID;

/**
 * Wallet JSON (contracts/openapi/v1/wallets.yaml Wallet). {@code closed_at}
 * is present only when status is closed — never in V1 (NON_NULL inclusion).
 */
public record WalletJson(String id, UUID principal_id, String currency, String status,
                         WalletBalancesJson balances, Instant created_at, Instant closed_at) {

    public static WalletJson of(Wallet wallet, com.sharkpay.wallet.domain.Balances balances) {
        return new WalletJson(wallet.id(), wallet.principalId(), wallet.currency(),
                wallet.status().wireName(), WalletBalancesJson.of(balances),
                wallet.createdAt(), null);
    }
}
