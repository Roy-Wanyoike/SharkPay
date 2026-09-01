package com.sharkpay.fx.config;

import com.sharkpay.fx.domain.Leg;
import com.sharkpay.fx.ports.LedgerLine;
import com.sharkpay.fx.ports.LedgerPort;
import com.sharkpay.fx.ports.LedgerStatement;

import java.util.List;

/**
 * Fail-fast placeholder {@link LedgerPort} adapter: posting the 4-leg FX
 * journal entry requires the Go ledger service's internal idempotent
 * posting API, wired at integration time by the integrator (ADR 003 §3).
 *
 * <p>Refusing loudly per call (instead of silently posting to an in-memory
 * map) keeps the money path honest: no conversion can execute against a
 * ledger that was never posted to.</p>
 */
public final class IntegrationPendingLedgerPort implements LedgerPort {

    @Override
    public String postTransaction(String idempotencyKey, List<Leg> legs) {
        throw new IllegalStateException("LedgerPort adapter is not wired yet: the REST ledger"
                + " posting adapter lands at integration time (ADR 003)."
                + " Cannot post transaction " + idempotencyKey + " (" + legs.size() + " legs).");
    }

    @Override
    public LedgerStatement getStatement(String accountRef) {
        throw new IllegalStateException("LedgerPort adapter is not wired yet: the REST ledger"
                + " posting adapter lands at integration time (ADR 003)."
                + " Cannot read the statement of " + accountRef + ".");
    }
}
