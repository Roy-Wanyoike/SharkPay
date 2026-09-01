package com.sharkpay.wallet.service;

import com.sharkpay.wallet.domain.Balances;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.ports.WalletRepository;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Read-side use-case for a single wallet: the wallet plus its balance
 * partitions (total = ledger projection, held = active holds, pending = 0,
 * available = total - held).
 */
public final class GetWalletUseCase {

    private final WalletRepository wallets;
    private final BalanceReader balances;

    public GetWalletUseCase(WalletRepository wallets, BalanceReader balances) {
        this.wallets = Objects.requireNonNull(wallets, "walletRepository is required");
        this.balances = Objects.requireNonNull(balances, "balanceReader is required");
    }

    /** The wallet with balances (404 when unknown). */
    public WalletWithBalances get(String walletId) {
        if (walletId == null || walletId.isBlank()) {
            throw new IllegalArgumentException("wallet id is required");
        }
        Wallet wallet = wallets.findById(walletId.trim())
                .orElseThrow(() -> new NoSuchElementException("wallet " + walletId + " not found"));
        return new WalletWithBalances(wallet, balances.balancesOf(wallet));
    }

    /** A wallet together with its current balance partitions. */
    public record WalletWithBalances(Wallet wallet, Balances balances) {
    }
}
