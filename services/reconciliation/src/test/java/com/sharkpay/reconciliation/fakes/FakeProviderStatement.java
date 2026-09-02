package com.sharkpay.reconciliation.fakes;

import com.sharkpay.money.Money;
import com.sharkpay.reconciliation.domain.ProviderStatementLine;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;
import com.sharkpay.reconciliation.ports.ProviderStatementPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-tree fake of the providers-gateway statement port (src/test, ADR 003
 * §3) and the executable specification of its contract:
 *
 * <ul>
 *   <li>the window filter is half-open {@code [from, to)} — a line at
 *       exactly {@code from} is served, a line at exactly {@code to} is
 *       not (matches {@code provider.Window});</li>
 *   <li>money is integer minor units via the money library;</li>
 *   <li>{@link #failNextFetch()} simulates the expected upstream failure
 *       (breaker open / unreachable) so a run can be driven to FAILED.</li>
 * </ul>
 */
public final class FakeProviderStatement implements ProviderStatementPort {

    private final List<ProviderStatementLine> lines = new CopyOnWriteArrayList<>();
    private final List<RecordedFetch> fetches = new CopyOnWriteArrayList<>();
    private volatile boolean failNext;

    /** Seeds one line (amount/fee in minor units). */
    public FakeProviderStatement seed(String ref, String status, long amountMinor, String currency,
                                      long feeMinor, Instant occurredAt) {
        lines.add(new ProviderStatementLine(ref, status, Money.of(amountMinor, currency),
                Money.of(feeMinor, currency), occurredAt));
        return this;
    }

    public FakeProviderStatement seed(ProviderStatementLine line) {
        lines.add(line);
        return this;
    }

    /** The next fetch throws the expected upstream-unavailable failure. */
    public FakeProviderStatement failNextFetch() {
        failNext = true;
        return this;
    }

    @Override
    public List<ProviderStatementLine> fetch(String provider, Instant from, Instant to) {
        fetches.add(new RecordedFetch(provider, from, to));
        if (failNext) {
            failNext = false;
            throw new StatementUnavailableException("provider statement", provider,
                    new IllegalStateException("circuit breaker open (seeded)"));
        }
        return lines.stream()
                .filter(line -> !line.occurredAt().isBefore(from) && line.occurredAt().isBefore(to))
                .toList();
    }

    /** Every fetch with its exact window (asserts the half-open semantics). */
    public List<RecordedFetch> fetches() {
        return List.copyOf(fetches);
    }

    public int fetchCount() {
        return fetches.size();
    }

    /** Drop all seeds and recorded fetches (between test phases). */
    public void reset() {
        lines.clear();
        fetches.clear();
        failNext = false;
    }

    public record RecordedFetch(String provider, Instant from, Instant to) {
    }
}
