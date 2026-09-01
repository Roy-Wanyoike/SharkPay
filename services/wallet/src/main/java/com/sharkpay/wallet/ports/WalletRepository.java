package com.sharkpay.wallet.ports;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.domain.WalletStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for wallets.
 */
public interface WalletRepository {

    Wallet save(Wallet wallet);

    Optional<Wallet> findById(String walletId);

    /** At most one wallet per principal per currency — the domain invariant. */
    Optional<Wallet> findByPrincipalAndCurrency(UUID principalId, String currency);

    /** Resolves the wallet that projects a given ledger account. */
    Optional<Wallet> findByLedgerAccountId(UUID ledgerAccountId);

    /**
     * Lists wallets ordered by id (stable pagination), optionally filtered.
     *
     * @param filter nullable-field filter (all null = no filtering)
     * @param limit  page size (> 0)
     * @param cursor last id of the previous page (null = first page)
     * @return wallets with id strictly greater than the cursor, up to limit
     */
    List<Wallet> list(WalletFilter filter, int limit, String cursor);

    /** Nullable-field wallet filter. */
    record WalletFilter(UUID principalId, String currency, WalletStatus status) {
    }
}
