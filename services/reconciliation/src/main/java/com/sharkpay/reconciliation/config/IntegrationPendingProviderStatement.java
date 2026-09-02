package com.sharkpay.reconciliation.config;

import com.sharkpay.reconciliation.domain.ProviderStatementLine;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;
import com.sharkpay.reconciliation.ports.ProviderStatementPort;

import java.time.Instant;
import java.util.List;

/**
 * Fail-fast placeholder for the providers-gateway reconciliation-report
 * adapter (ADR 003 §3): calling it before integration wiring is a loud
 * programming error, never a silent wrong answer. The real adapter POSTs
 * {@code /v1/providers/{name}/reconcile} on the providers service; the
 * in-tree fake (src/test) is the executable specification of the wire
 * contract.
 */
public final class IntegrationPendingProviderStatement implements ProviderStatementPort {

    @Override
    public List<ProviderStatementLine> fetch(String provider, Instant from, Instant to) {
        throw new StatementUnavailableException("provider statement", provider,
                new IllegalStateException("integration pending: the providers-gateway REST adapter "
                        + "is wired by the integrator (ADR 003 §3) — POST "
                        + "/v1/providers/" + provider + "/reconcile from=" + from + " to=" + to));
    }
}
