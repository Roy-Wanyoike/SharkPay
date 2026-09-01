package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.events.PaymentEvents;
import com.sharkpay.payments.ports.EventPublisher;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.ports.PaymentRepository;
import com.sharkpay.payments.ports.WalletHoldPort;

import java.time.Clock;
import java.util.Objects;

/**
 * Places the funds-control hold for a CREATED intent:
 * ledger {@code HOLD} journal entry → wallet hold →
 * CREATED → PENDING_PROVIDER (docs/STATE-MACHINES.md §1 side effect
 * "hold entry posted") + the {@code payments.payment.pending_provider.v1}
 * event.
 *
 * <p>Idempotent: an intent that is not CREATED (hold already placed, blocked
 * or terminal) is returned unchanged with {@code skipped=true} — Temporal
 * activity at-least-once delivery and the synchronous REST prefix can both
 * run this safely with no double hold (ADR 003 G2).</p>
 */
public final class PlaceHoldUseCase {

    private final PaymentRepository payments;
    private final WalletHoldPort walletHolds;
    private final LedgerPort ledger;
    private final PaymentEvents events;
    private final EventPublisher publisher;
    private final Clock clock;

    public PlaceHoldUseCase(PaymentRepository payments, WalletHoldPort walletHolds,
                            LedgerPort ledger, PaymentEvents events, EventPublisher publisher,
                            Clock clock) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.walletHolds = Objects.requireNonNull(walletHolds, "walletHoldPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.events = Objects.requireNonNull(events, "paymentEvents is required");
        this.publisher = Objects.requireNonNull(publisher, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Places the hold and moves the intent to PENDING_PROVIDER. */
    public Result place(String paymentId) {
        PaymentIntent intent = load(paymentId);
        if (intent.state() != PaymentState.CREATED) {
            return new Result(intent, null, true);
        }
        String walletId = intent.destination().internalWalletId()
                .orElseThrow(() -> new IllegalStateException(
                        "payment " + intent.id() + " has no internal wallet destination"));
        java.util.UUID holdEntryId = ledger.postEntry(intent.internalId(), LedgerPort.EntryType.HOLD,
                walletId, intent.amount(), "payment hold " + intent.id());
        String holdId = walletHolds.placeHold(walletId, intent.amount(), intent.internalId());
        intent.markPendingProvider(holdId, holdEntryId, clock.instant());
        payments.save(intent);
        publisher.publish(events.pendingProvider(intent, holdEntryId, clock.instant()));
        return new Result(intent, holdId, false);
    }

    private PaymentIntent load(String paymentId) {
        return payments.findById(Objects.requireNonNull(paymentId, "paymentId is required"))
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
    }

    /**
     * @param intent  the intent after placement (PENDING_PROVIDER)
     * @param holdId  the wallet-side hold id (null when skipped)
     * @param skipped true when the intent was not in CREATED
     */
    public record Result(PaymentIntent intent, String holdId, boolean skipped) {
    }
}
