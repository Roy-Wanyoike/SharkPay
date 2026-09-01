package com.sharkpay.wallet.ports;

import java.util.UUID;

/**
 * Port: provision (idempotently) the ledger account backing a wallet.
 * The real adapter calls the ledger service's account API
 * ({@code POST /internal/v1/accounts}, code {@code wallet:<principal>:<currency>},
 * type {@code wallet}, owner = principal) and returns the account id; the
 * ledger deduplicates on the account code. The wallet projection matches
 * {@code ledger.posting.committed.v1} legs against this account id.
 */
public interface LedgerAccounts {

    /**
     * Returns the ledger account id for the principal-currency pair,
     * creating the account if needed. Must be idempotent per pair.
     */
    UUID provisionWalletAccount(UUID principalId, String currency);
}
