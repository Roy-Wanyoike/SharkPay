package com.sharkpay.wallet.api.dto;

import com.sharkpay.money.Money;

/**
 * Wallet balances JSON (contracts/openapi/v1/wallets.yaml WalletBalances):
 * the three partitions, canonical money objects.
 */
public record WalletBalancesJson(MoneyJson available, MoneyJson pending, MoneyJson held) {

    public static WalletBalancesJson of(com.sharkpay.wallet.domain.Balances balances) {
        return new WalletBalancesJson(MoneyJson.of(balances.available()),
                MoneyJson.of(balances.pending()), MoneyJson.of(balances.held()));
    }
}
