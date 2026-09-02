package com.sharkpay.payouts.fakes;

import com.sharkpay.money.Money;
import com.sharkpay.payouts.ports.WalletHoldPort;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link WalletHoldPort} fake (the wallet snapshot REST adapter
 * lands at integration, ADR 003 §3): wallet ids are registered with their
 * read-side snapshot — owner principal, currency, status, available
 * balance and the ledger account its journal legs key on. Unregistered ids
 * return empty so the use-cases exercise their 404 path. This is the cheap
 * pre-validation read; the fake ledger stays the money authority.
 */
public final class FakeWalletHoldPort implements WalletHoldPort {

    private final Map<String, WalletSnapshot> wallets = new ConcurrentHashMap<>();
    private final Map<String, Money> availableByWallet = new ConcurrentHashMap<>();

    /** Registers an ACTIVE wallet with its available balance + ledger account. */
    public FakeWalletHoldPort addWallet(String walletId, UUID principalId, String currency,
                                        long availableMinor, UUID ledgerAccountId) {
        Money available = Money.of(availableMinor, currency);
        wallets.put(walletId, new WalletSnapshot(walletId, principalId, currency,
                WalletStatus.ACTIVE, available, ledgerAccountId));
        availableByWallet.put(walletId, available);
        return this;
    }

    /** Freezes a registered wallet (status FROZEN — money cannot move). */
    public FakeWalletHoldPort freeze(String walletId) {
        WalletSnapshot snapshot = wallets.get(walletId);
        if (snapshot == null) {
            throw new IllegalArgumentException("unknown wallet " + walletId);
        }
        wallets.put(walletId, new WalletSnapshot(snapshot.walletId(), snapshot.principalId(),
                snapshot.currency(), WalletStatus.FROZEN, snapshot.available(),
                snapshot.ledgerAccountId()));
        return this;
    }

    @Override
    public Optional<WalletSnapshot> findWallet(String walletId) {
        return Optional.ofNullable(wallets.get(walletId == null ? "" : walletId.trim()));
    }

    /** The registered snapshot, when present. */
    public WalletSnapshot snapshot(String walletId) {
        return wallets.get(walletId);
    }

    /** The spendable balance a wallet reports. */
    public Money availableOf(String walletId) {
        return availableByWallet.get(walletId);
    }
}
