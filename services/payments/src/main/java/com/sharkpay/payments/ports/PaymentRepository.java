package com.sharkpay.payments.ports;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.StateTransition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage port for the PaymentIntent aggregate + the append-only transition
 * audit log. Saving drains the aggregate's pending transitions into
 * {@code payment_state_transitions} (every state change is persisted —
 * STATE-MACHINES.md §7.3 replayability).
 */
public interface PaymentRepository {

    /** Persists the aggregate snapshot and appends its new transition rows. */
    PaymentIntent save(PaymentIntent intent);

    Optional<PaymentIntent> findById(String paymentId);

    /** Full transition timeline of one intent (support / recon replay). */
    List<StateTransition> transitionsOf(String paymentId);

    /** Cursor-paginated, filtered listing (payments.yaml listPayments). */
    Page list(PaymentFilter filter);

    /**
     * @param state      exact state filter (null = any)
     * @param principalId owning principal filter (null = any)
     * @param createdFrom include intents created at or after (null = any)
     * @param createdTo   include intents created strictly before (null = any)
     * @param limit       page size 1..100 (default 50)
     * @param cursor      opaque cursor from a previous page (null = first)
     */
    record PaymentFilter(PaymentState state, UUID principalId, Instant createdFrom,
                         Instant createdTo, Integer limit, String cursor) {

        public int effectiveLimit() {
            if (limit == null) {
                return 50;
            }
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be within [1, 100]: " + limit);
            }
            return limit;
        }
    }

    record Page(List<PaymentIntent> items, String nextCursor) {

        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
