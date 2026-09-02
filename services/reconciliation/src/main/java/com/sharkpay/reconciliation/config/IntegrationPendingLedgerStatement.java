package com.sharkpay.reconciliation.config;

import com.sharkpay.reconciliation.domain.InternalLedgerLine;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;
import com.sharkpay.reconciliation.ports.LedgerStatementPort;

import java.time.Instant;
import java.util.List;

/**
 * Fail-fast placeholder for the ledger statement adapter (ADR 003 §3). The
 * real adapter pages {@code GET /internal/accounts/{id}/statement} on the
 * Go ledger for the provider's clearing/settlement accounts and shapes the
 * postings into {@link InternalLedgerLine}s (half-open [from, to) window);
 * the in-tree fake (src/test) is the executable specification.
 */
public final class IntegrationPendingLedgerStatement implements LedgerStatementPort {

    @Override
    public List<InternalLedgerLine> internalLines(String provider, Instant from, Instant to) {
        throw new StatementUnavailableException("ledger statement", provider,
                new IllegalStateException("integration pending: the ledger REST adapter is wired "
                        + "by the integrator (ADR 003 §3) — GET /internal/accounts/{id}/statement "
                        + "for provider=" + provider + " window=[" + from + ", " + to + ")"));
    }
}
