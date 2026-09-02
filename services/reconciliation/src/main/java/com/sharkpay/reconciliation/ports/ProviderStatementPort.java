package com.sharkpay.reconciliation.ports;

import com.sharkpay.reconciliation.domain.ProviderStatementLine;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;

import java.time.Instant;
import java.util.List;

/**
 * Consumer-driven port to the providers gateway's reconciliation report:
 * {@code POST /v1/providers/{name}/reconcile} with {@code {from, to}} →
 * {@code {"lines": [ProviderLine…]}} (services/providers
 * cmd/server/main.go). The window is half-open {@code [from, to)} —
 * adapters must apply exactly that filter to the provider's activity.
 *
 * <p>The wire {@code provider.ProviderLine} shape ({@code Ref}, raw
 * {@code Status} string, {@code Money} amount/fee with
 * {@code amount_minor/currency/exponent}, {@code OccurredAt} RFC 3339) maps
 * one-to-one onto {@link ProviderStatementLine}; money is constructed via
 * the money library (validated, integer minor units). The in-tree fake
 * (src/test, ADR 003 §3) doubles as the executable specification of this
 * contract; the production REST adapter lands at integration (fail-fast
 * placeholder in {@code com.sharkpay.reconciliation.config} until then).</p>
 */
public interface ProviderStatementPort {

    /**
     * Fetches the provider's statement lines inside {@code [from, to)}.
     *
     * @throws StatementUnavailableException the provider could not serve
     *                                       the report (unreachable, breaker
     *                                       open, transport error) — an
     *                                       expected failure mode: the run
     *                                       is marked FAILED, never guessed
     */
    List<ProviderStatementLine> fetch(String provider, Instant from, Instant to);
}
