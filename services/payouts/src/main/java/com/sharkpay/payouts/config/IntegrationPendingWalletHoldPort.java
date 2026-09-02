package com.sharkpay.payouts.config;

import com.sharkpay.payouts.ports.WalletHoldPort;

import java.util.Optional;
import java.util.UUID;

/**
 * Fail-fast placeholder {@link WalletHoldPort} adapter: wallet snapshots
 * come from the wallet service's internal read API
 * (GET /internal/wallets/{id}), wired at integration time by the
 * integrator (ADR 003 §3).
 */
public final class IntegrationPendingWalletHoldPort implements WalletHoldPort {

    @Override
    public Optional<WalletSnapshot> findWallet(String walletId) {
        throw new IllegalStateException("WalletHoldPort adapter is not wired yet: the wallet "
                + "snapshot REST adapter lands at integration time (ADR 003). Cannot read "
                + "wallet " + walletId + ".");
    }
}
