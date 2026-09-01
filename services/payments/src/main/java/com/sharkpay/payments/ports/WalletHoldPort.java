package com.sharkpay.payments.ports;

import com.sharkpay.money.Money;

import java.util.UUID;

/**
 * Consumer-driven port (ADR 003 §3) to the wallet service's funds-control
 * API: place / release / capture a hold. The hold is the wallet-side
 * reservation of the in-flight collected amount (pending partition): posted
 * when the intent goes PENDING_PROVIDER (STATE-MACHINES.md §1 "hold entry
 * posted"), released on fail/expire/cancel, captured (pending → settled)
 * when the rail confirms.
 *
 * <p><b>Idempotency contract:</b> every operation is keyed by
 * {@code sourceRef} (the payment intent's internal UUID) plus its effect —
 * re-invoking place/release/capture for the same sourceRef is a no-op that
 * returns/keeps the original outcome. This is what makes at-least-once
 * activity delivery (Temporal) and REST retries safe (ADR 003 G2: same key
 * ⇒ same result, no double effect).</p>
 */
public interface WalletHoldPort {

    /** Whether the wallet exists (create-time 404 check). */
    boolean walletExists(String walletId);

    /**
     * Places a hold of {@code amount} on the wallet's in-flight funds.
     *
     * @param walletId  destination wallet ({@code wal_...})
     * @param amount    amount to reserve (positive, payment currency)
     * @param sourceRef payment intent internal id (idempotency key)
     * @return the wallet-side hold id ({@code hld_...})
     */
    String placeHold(String walletId, Money amount, UUID sourceRef);

    /**
     * Releases the hold: the in-flight reservation disappears (funds never
     * arrived or are returned). Idempotent.
     */
    void releaseHold(String holdId, UUID sourceRef);

    /**
     * Captures the held amount as settled funds (pending → available).
     * Idempotent; a second capture for the same sourceRef is a no-op.
     *
     * @param amount the amount to settle (must be ≤ the held amount)
     */
    void captureHold(String holdId, Money amount, UUID sourceRef);
}
