package com.sharkpay.wallet.ports;

import com.sharkpay.wallet.domain.ProjectionLeg;
import com.sharkpay.wallet.domain.StatementLine;

import java.util.List;

/**
 * Persistence port for the ledger balance projection (read model).
 *
 * <p>Implementations must apply legs posting-ordered: {@code balance_after}
 * values and totals are recomputed in {@code posting_id} order so that
 * out-of-order event delivery converges, and {@link #applyLeg} must be a
 * no-op (returning false) for an already-projected {@code (wallet, posting)}
 * pair so duplicate delivery never double-applies.
 */
public interface ProjectionStore {

    /** Fast-path dedup: has this ledger event id already been applied? */
    boolean isEventApplied(String eventId);

    /** Marks the event id as applied (idempotent; entry id recorded for audit). */
    void markEventApplied(String eventId, java.util.UUID entryId);

    /**
     * Applies one wallet leg.
     *
     * @return true when newly applied, false when already present
     * @throws com.sharkpay.wallet.domain.ProjectionInconsistencyException on
     *         currency mismatch, overflow or below-zero balance
     */
    boolean applyLeg(String walletId, ProjectionLeg leg);

    /**
     * The wallet's statement lines in posting order (with running balances),
     * at most {@code limit} lines after the cursor.
     *
     * @param afterPostingId cursor: only lines with a greater posting id
     *                       (null = from the beginning)
     */
    List<StatementLine> statement(String walletId, int limit, Long afterPostingId);

    /** The wallet's total (ledger) balance in minor units (0 when empty). */
    long totalMinor(String walletId);
}
