package com.sharkpay.reconciliation.ports;

import com.sharkpay.reconciliation.domain.InternalLedgerLine;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;

import java.time.Instant;
import java.util.List;

/**
 * Consumer-driven port to the internal ledger postings of a window — the
 * internal side of the comparison. The production adapter reads the Go
 * ledger's account-statement surface ({@code GET
 * /internal/accounts/{id}/statement}, cursor-paginated
 * {@code services/ledger/internal/domain/statement.go}) for the provider's
 * clearing/settlement accounts (e.g. {@code honeycoin:clearing:KES}) and
 * shapes the postings into {@link InternalLedgerLine}s: one line per
 * internal movement with its principal amount, fee, status (derived from
 * entry types and the owning service's state) and the provider ref the
 * movement carries (transaction key / source ref).
 *
 * <p>Contract rules the adapter (and the in-tree fake) must honour:</p>
 * <ul>
 *   <li>the window is half-open {@code [from, to)} — a line with
 *       {@code occurredAt == from} is included, {@code occurredAt == to}
 *       is excluded (same semantics as the providers window);</li>
 *   <li>internal lines are unique by provider ref — the comparison engine
 *       rejects a duplicate loudly instead of misclassifying;</li>
 *   <li>money is integer minor units via the money library, never
 *       floats.</li>
 * </ul>
 */
public interface LedgerStatementPort {

    /**
     * The internal ledger lines inside {@code [from, to)} relevant to
     * {@code provider}'s accounts.
     *
     * @throws StatementUnavailableException the ledger could not serve the
     *                                       statement — the run is marked
     *                                       FAILED, never guessed
     */
    List<InternalLedgerLine> internalLines(String provider, Instant from, Instant to);
}
