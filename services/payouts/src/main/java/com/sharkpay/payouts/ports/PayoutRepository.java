package com.sharkpay.payouts.ports;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Payout persistence port. {@link #save(Payout)} upserts the aggregate and
 * appends its {@link Payout#pendingTransitions()} to the
 * {@code payout_state_transitions} audit table before marking them
 * persisted. The three scheduler queries mirror the partial indexes of
 * V1__payouts_init.sql:
 *
 * <ul>
 *   <li>{@link #findDueForRelease} — PENDING_RISK payouts with
 *       {@code executeAfter ≤ now} whose backoff retry time (if any) has
 *       passed, execute_after ascending (the release batch);</li>
 *   <li>{@link #findExpired} — PENDING_RISK/PROCESSING payouts past
 *       {@code expiresAt} (the TTL sweep batch);</li>
 *   <li>{@link #findInFlight} — PROCESSING/SENT payouts, oldest-updated
 *       first (the provider polling batch).</li>
 * </ul>
 */
public interface PayoutRepository {

    Payout save(Payout payout);

    Optional<Payout> findById(String payoutId);

    /** The due-for-release batch (bounded by {@code limit}). */
    List<Payout> findDueForRelease(Instant now, int limit);

    /** The TTL-expiry sweep batch (bounded by {@code limit}). */
    List<Payout> findExpired(Instant now, int limit);

    /** The in-flight provider-polling batch (bounded by {@code limit}). */
    List<Payout> findInFlight(int limit);

    /** Count of payouts in a state (ops/test introspection). */
    long countByState(PayoutState state);
}
