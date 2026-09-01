package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.StateTransition;
import com.sharkpay.payments.ports.PaymentRepository;
import com.sharkpay.payments.ports.PaymentRepository.Page;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read side: cursor-paginated listing (payments.yaml listPayments) and the
 * transition timeline (internal replay, STATE-MACHINES.md §7.3). Limit
 * outside [1, 100] is a 400 validation error.
 */
public final class ListPaymentsUseCase {

    private final PaymentRepository payments;

    public ListPaymentsUseCase(PaymentRepository payments) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
    }

    /** @param state canonical PaymentState wire value (null = any) */
    public Page list(String state, UUID principalId, Instant createdFrom, Instant createdTo,
                     Integer limit, String cursor) {
        com.sharkpay.payments.domain.PaymentState stateFilter = null;
        if (state != null && !state.isBlank()) {
            try {
                stateFilter = com.sharkpay.payments.domain.PaymentState.fromWire(state.trim());
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException("unknown state: " + state);
            }
        }
        if (limit != null && (limit < 1 || limit > 100)) {
            throw new IllegalArgumentException("limit must be within [1, 100]: " + limit);
        }
        if (cursor != null && cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }
        return payments.list(new com.sharkpay.payments.ports.PaymentRepository.PaymentFilter(
                stateFilter, principalId, createdFrom, createdTo, limit, cursor));
    }

    /** Full transition timeline of one intent (404 when the intent is unknown). */
    public List<StateTransition> timeline(String paymentId) {
        Objects.requireNonNull(paymentId, "paymentId is required");
        payments.findById(paymentId)
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
        return payments.transitionsOf(paymentId);
    }
}
