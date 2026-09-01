package com.sharkpay.payments.ports;

import com.sharkpay.money.Money;

import java.util.UUID;

/**
 * Consumer-driven port (ADR 003 §3) to the Go ledger's posting API. The
 * ledger is the sole money authority (docs/BACKEND-DESIGN.md §3): payments
 * posts journal entries for the state machine's money side effects and never
 * computes balances itself.
 *
 * <p><b>Idempotency contract:</b> entries are keyed
 * {@code (paymentId, EntryType)} — posting the same entry twice returns the
 * original entry id with no second journal effect; reversals are keyed
 * {@code (paymentId, REVERSAL)}. This is the exactly-once ledger pairing the
 * reconciliation service validates daily (STATE-MACHINES.md §7.4).</p>
 *
 * <p><b>Compensation rule (docs/BACKEND-DESIGN.md §6):</b> a compensation is
 * ALWAYS a reversal/release entry — never an in-place mutation of a posted
 * entry.</p>
 */
public interface LedgerPort {

    /**
     * Posts (or replays) the journal entry for one payment money side
     * effect.
     *
     * @param paymentId payment intent internal id (idempotency key part)
     * @param type      HOLD / RELEASE / CAPTURE / REVERSAL
     * @param walletId  the wallet account the entry moves
     * @param amount    entry amount (positive, minor units)
     * @param reason    audit note
     * @return the ledger journal entry id
     */
    UUID postEntry(UUID paymentId, EntryType type, String walletId, Money amount, String reason);

    /**
     * Posts (or replays) the reversal of a previously posted entry.
     *
     * @return the reversal journal entry id
     */
    UUID reverseEntry(UUID entryId, UUID paymentId, String reason);

    /** Money side effects of the payment state machine (§1 side-effect column). */
    enum EntryType {
        HOLD, RELEASE, CAPTURE, REVERSAL
    }
}
