package com.sharkpay.reconciliation.fakes;

import com.sharkpay.money.Money;
import com.sharkpay.reconciliation.domain.InternalLedgerLine;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;
import com.sharkpay.reconciliation.ports.LedgerStatementPort;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-tree fake of the ledger statement port (src/test, ADR 003 §3): the
 * internal side of the comparison, seedable with deliberate
 * discrepancies. Contract (the executable spec the real adapter must
 * satisfy):
 *
 * <ul>
 *   <li>half-open window {@code [from, to)} — identical semantics to the
 *       provider side;</li>
 *   <li>internal lines unique by provider ref (duplicates are the port
 *       adapter's bug, never the engine's guess);</li>
 *   <li>money via the money library (integer minor units, validated
 *       currency);</li>
 *   <li>{@link #failNextFetch()} drives a run to FAILED.</li>
 * </ul>
 */
public final class FakeLedgerStatement implements LedgerStatementPort {

    private final List<InternalLedgerLine> lines = new CopyOnWriteArrayList<>();
    private final List<RecordedFetch> fetches = new CopyOnWriteArrayList<>();
    private volatile boolean failNext;

    /**
     * Seeds one internal line; {@code providerRef} null marks an internal
     * movement that never referenced a provider transfer (unmatchable).
     */
    public FakeLedgerStatement seed(String internalRef, String providerRef, String status,
                                    long amountMinor, String currency, long feeMinor,
                                    Instant occurredAt) {
        lines.add(new InternalLedgerLine(internalRef, providerRef, status,
                Money.of(amountMinor, currency), Money.of(feeMinor, currency), occurredAt));
        return this;
    }

    public FakeLedgerStatement failNextFetch() {
        failNext = true;
        return this;
    }

    @Override
    public List<InternalLedgerLine> internalLines(String provider, Instant from, Instant to) {
        fetches.add(new RecordedFetch(provider, from, to));
        if (failNext) {
            failNext = false;
            throw new StatementUnavailableException("ledger statement", provider,
                    new IllegalStateException("ledger unreachable (seeded)"));
        }
        return lines.stream()
                .filter(line -> !line.occurredAt().isBefore(from) && line.occurredAt().isBefore(to))
                .toList();
    }

    public List<RecordedFetch> fetches() {
        return List.copyOf(fetches);
    }

    public void reset() {
        lines.clear();
        fetches.clear();
        failNext = false;
    }

    public record RecordedFetch(String provider, Instant from, Instant to) {
    }
}
