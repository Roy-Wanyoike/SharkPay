package com.sharkpay.wallet.config;

import com.sharkpay.wallet.ports.LedgerAccounts;

import java.util.UUID;

/**
 * Fail-fast placeholder {@link LedgerAccounts} adapter: provisioning a wallet
 * ledger account requires the Go ledger service's account API
 * ({@code POST /internal/v1/accounts}), which is wired at integration time
 * by the integrator (ADR 003 §3 — REST clients land once, centrally).
 *
 * <p>Refusing loudly per call (instead of silently provisioning fake account
 * ids) keeps the money path honest: no wallet can be created against a
 * ledger account that does not exist.</p>
 */
public final class IntegrationPendingLedgerAccounts implements LedgerAccounts {

    @Override
    public UUID provisionWalletAccount(UUID principalId, String currency) {
        throw new IllegalStateException("LedgerAccounts adapter is not wired yet: the REST"
                + " ledger-account provisioner lands at integration time (ADR 003)."
                + " Cannot provision wallet account for principal " + principalId
                + " (" + currency + ").");
    }
}
