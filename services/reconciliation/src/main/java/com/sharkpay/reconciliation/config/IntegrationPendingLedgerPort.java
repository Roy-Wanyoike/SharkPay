package com.sharkpay.reconciliation.config;

import com.sharkpay.reconciliation.ports.LedgerPort;

/**
 * Fail-fast placeholder for the ledger posting adapter (ADR 003 §3). The
 * real adapter POSTs compensation entries to
 * {@code /internal/transactions} on the Go ledger (idempotent on the
 * compensation key); the in-tree fake (src/test) enforces every structural
 * invariant the real ledger enforces and is the executable specification.
 */
public final class IntegrationPendingLedgerPort implements LedgerPort {

    @Override
    public PostingResult post(LedgerPosting posting) {
        throw new IllegalStateException("integration pending: the ledger REST adapter is wired by "
                + "the integrator (ADR 003 §3) — POST /internal/transactions with key "
                + posting.transactionKey());
    }
}
