package com.sharkpay.payouts.ports;

import com.sharkpay.money.Money;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-driven port to the wallet service's internal read API
 * (pre-validation reads — optional but cheap: the ledger remains the sole
 * money authority). Resolves a wallet id to its snapshot: owning principal,
 * currency, status, available balance (total minus active holds, the same
 * number the wallet service reports) and the ledger account its journal
 * legs key on.
 *
 * <p>Production adapter (REST against
 * {@code GET /internal/wallets/{id}}) lands at integration; local tests run
 * the in-tree fake (ADR 003 §3).</p>
 */
public interface WalletHoldPort {

    Optional<WalletSnapshot> findWallet(String walletId);

    /** Wallet status (wallet service states). */
    enum WalletStatus { ACTIVE, FROZEN }

    /**
     * A wallet's read-side snapshot. {@code available} is the spendable
     * balance after holds; {@code ledgerAccountId} is the account id the
     * ledger legs must reference.
     */
    record WalletSnapshot(String walletId, UUID principalId, String currency,
                          WalletStatus status, Money available, UUID ledgerAccountId) {

        public WalletSnapshot {
            if (walletId == null || walletId.isBlank()) {
                throw new IllegalArgumentException("walletId is required");
            }
            Objects.requireNonNull(principalId, "principalId is required");
            Objects.requireNonNull(currency, "currency is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(available, "available is required");
            Objects.requireNonNull(ledgerAccountId, "ledgerAccountId is required");
        }

        public boolean isActive() {
            return status == WalletStatus.ACTIVE;
        }
    }
}
