package com.sharkpay.payments.service;

import com.sharkpay.money.Money;
import com.sharkpay.payments.domain.IdempotencyConflictException;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.ReversalExceedsCapturedException;
import com.sharkpay.payments.events.PaymentEvents;
import com.sharkpay.payments.ports.EventPublisher;
import com.sharkpay.payments.ports.IdempotencyStore;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.ports.PaymentRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Reverse a payment (STATE-MACHINES.md §1: SUCCEEDED → REVERSED
 * "reversal", FAILED → REVERSED "late funds recovered"): posts the ledger
 * compensation pair (reversal of the capture entry, or a standalone
 * REVERSAL entry for the late-funds path) and publishes
 * {@code payments.payment.reversed.v1}.
 *
 * <p>Guard: the reversal amount must be ≤ the captured amount (the intent's
 * amount for a succeeded payment); anything above is a 422
 * {@code reversal_exceeds_captured}. Idempotent on the Idempotency-Key.</p>
 */
public final class ReversePaymentUseCase {

    private final PaymentRepository payments;
    private final LedgerPort ledger;
    private final IdempotencyStore idempotency;
    private final PaymentEvents events;
    private final EventPublisher publisher;
    private final Clock clock;

    public ReversePaymentUseCase(PaymentRepository payments, LedgerPort ledger,
                                 IdempotencyStore idempotency, PaymentEvents events,
                                 EventPublisher publisher, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "paymentEvents is required");
        this.publisher = Objects.requireNonNull(publisher, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param amountMinor the reversal amount (null = the full amount)
     * @param reason      audit reason (nullable)
     */
    public Result reverse(String idempotencyKey, String paymentId, Long amountMinor, String reason) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(paymentId, "paymentId is required");
        String key = idempotencyKey.trim();
        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.REVERSE_PAYMENT, key);
        if (stored.isPresent()) {
            if (!stored.get().requestFingerprint()
                    .equals(fingerprint(paymentId, amountMinor))) {
                throw new IdempotencyConflictException(key);
            }
            PaymentIntent original = payments.findById(stored.get().entityId())
                    .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(
                            stored.get().entityId()));
            return new Result(original, true);
        }
        PaymentIntent intent = payments.findById(paymentId)
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
        if (intent.state() != PaymentState.SUCCEEDED && intent.state() != PaymentState.FAILED) {
            throw new com.sharkpay.payments.domain.PaymentStateException(intent.id(),
                    intent.state(), PaymentState.REVERSED);
        }
        Money reversal = amountMinor == null ? intent.amount()
                : Money.of(amountMinor, intent.amount().currency());
        if (reversal.isNegative() || reversal.compareTo(intent.amount()) > 0) {
            throw new ReversalExceedsCapturedException(intent.id());
        }
        java.util.UUID reversalEntryId;
        if (intent.captureEntryId() != null) {
            reversalEntryId = ledger.reverseEntry(intent.captureEntryId(), intent.internalId(),
                    "payment reversal " + intent.id() + ": " + reason);
        } else {
            String walletId = intent.destination().internalWalletId().orElse("external");
            reversalEntryId = ledger.postEntry(intent.internalId(), LedgerPort.EntryType.REVERSAL,
                    walletId, reversal, "payment reversal " + intent.id() + ": " + reason);
        }
        intent.markReversed(reason == null || reason.isBlank() ? "reversal" : reason,
                reversalEntryId, reversal, clock.instant());
        payments.save(intent);
        idempotency.put(IdempotencyStore.Scope.REVERSE_PAYMENT, key,
                new IdempotencyStore.StoredRequest(fingerprint(paymentId, amountMinor),
                        intent.id()));
        publisher.publish(events.reversed(intent, reason, reversalEntryId, clock.instant()));
        return new Result(intent, false);
    }

    static String fingerprint(String paymentId, Long amountMinor) {
        return "REVERSE_PAYMENT|" + paymentId + "|" + (amountMinor == null ? "full" : amountMinor);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
    }

    /** @param replay true when served from the idempotency store */
    public record Result(PaymentIntent intent, boolean replay) {
    }
}
