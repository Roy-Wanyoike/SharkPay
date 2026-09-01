package com.sharkpay.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the append-only {@code payment_state_transitions} audit table:
 * every state change of a payment intent is persisted here
 * (STATE-MACHINES.md §7.3 "replayability"; ADR 003 G2). The initial creation
 * row carries {@code from == null}; every other row has a real from-state.
 *
 * @param paymentId  public intent id ({@code pay_...})
 * @param seq        per-payment monotonic sequence (1, 2, ...)
 * @param from       previous state (null on the creation row)
 * @param to         new state
 * @param reason     why the transition happened (risk reason, provider
 *                   failure reason, "ttl_elapsed", ...)
 * @param entryId    the ledger entry tied to the transition's money side
 *                   effect (hold / release / capture / reversal), when one
 *                   was posted
 * @param occurredAt when the transition happened
 */
public record StateTransition(String paymentId, long seq, PaymentState from, PaymentState to,
                              String reason, UUID entryId, Instant occurredAt) {

    public StateTransition {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("transition paymentId is required");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("transition seq must be >= 1: " + seq);
        }
        if (to == null) {
            throw new IllegalArgumentException("transition to-state is required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("transition occurredAt is required");
        }
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    /** Wire-friendly line (ops console / support timeline). */
    @Override
    public String toString() {
        return (from == null ? "∅" : from.wireName()) + " → " + to.wireName()
                + (reason == null ? "" : " (" + reason + ")")
                + (entryId == null ? "" : " entry=" + entryId)
                + " @ " + occurredAt;
    }
}
