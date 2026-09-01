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
 * Expiry: the intent's TTL elapsed unconfirmed. Guard
 * (STATE-MACHINES.md §1): expiry only from PENDING_PROVIDER — an intent that
 * confirmed, failed, was cancelled or blocked in the meantime is returned
 * unchanged. The hold is released exactly once (wallet hold + ledger
 * {@code RELEASE} entry, both idempotent on the payment ref) and the
 * {@code payments.payment.expired.v1} event is published.
 */
public final class ExpirePaymentUseCase {

    private final PaymentRepository payments;
    private final WalletHoldPort walletHolds;
    private final LedgerPort ledger;
    private final PaymentEvents events;
    private final EventPublisher publisher;
    private final Clock clock;

    public ExpirePaymentUseCase(PaymentRepository payments, WalletHoldPort walletHolds,
                                LedgerPort ledger, PaymentEvents events, EventPublisher publisher,
                                Clock clock) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.walletHolds = Objects.requireNonNull(walletHolds, "walletHoldPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.events = Objects.requireNonNull(events, "paymentEvents is required");
        this.publisher = Objects.requireNonNull(publisher, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Expires the intent if its TTL elapsed while PENDING_PROVIDER. */
    public Result expire(String paymentId) {
        PaymentIntent intent = load(paymentId);
        if (intent.state() != PaymentState.PENDING_PROVIDER
                || !intent.isExpiredAt(clock.instant())) {
            return new Result(intent, true);
        }
        java.util.UUID releaseEntryId = null;
        if (intent.holdId() != null) {
            String walletId = intent.destination().internalWalletId().orElse("external");
            releaseEntryId = ledger.postEntry(intent.internalId(), LedgerPort.EntryType.RELEASE,
                    walletId, intent.amount(), "payment expiry release " + intent.id());
            walletHolds.releaseHold(intent.holdId(), intent.internalId());
        }
        intent.markExpired(releaseEntryId, clock.instant());
        payments.save(intent);
        publisher.publish(events.expired(intent, "ttl_elapsed", releaseEntryId, clock.instant()));
        return new Result(intent, false);
    }

    private PaymentIntent load(String paymentId) {
        return payments.findById(Objects.requireNonNull(paymentId, "paymentId is required"))
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
    }

    /** @param skipped true when the intent was not an expired PENDING_PROVIDER */
    public record Result(PaymentIntent intent, boolean skipped) {
    }
}
