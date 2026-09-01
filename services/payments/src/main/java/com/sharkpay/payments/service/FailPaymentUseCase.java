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
 * Saga compensation for a failing payment (provider reject, hard rail error,
 * post-authorization risk deny, no routable provider): the hold is released
 * (wallet + ledger {@code RELEASE} entry) and the intent lands in FAILED
 * with the reason. Compensation is ALWAYS release/reversal — never an
 * in-place mutation of a posted entry (docs/BACKEND-DESIGN.md §6).
 *
 * <p>Exactly-once: an intent already in a terminal state is returned
 * unchanged with {@code skipped=true}; the ledger's
 * {@code (paymentId, RELEASE)} idempotency plus the wallet hold's
 * {@code sourceRef} idempotency make double delivery a no-op even without
 * the state guard (ADR 003 G2).</p>
 */
public final class FailPaymentUseCase {

    private final PaymentRepository payments;
    private final WalletHoldPort walletHolds;
    private final LedgerPort ledger;
    private final PaymentEvents events;
    private final EventPublisher publisher;
    private final Clock clock;

    public FailPaymentUseCase(PaymentRepository payments, WalletHoldPort walletHolds,
                              LedgerPort ledger, PaymentEvents events, EventPublisher publisher,
                              Clock clock) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.walletHolds = Objects.requireNonNull(walletHolds, "walletHoldPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.events = Objects.requireNonNull(events, "paymentEvents is required");
        this.publisher = Objects.requireNonNull(publisher, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Compensates and fails the intent with {@code reason}. */
    public Result fail(String paymentId, String reason) {
        Objects.requireNonNull(reason, "reason is required");
        PaymentIntent intent = load(paymentId);
        // idempotent guard: failure is legal only from PENDING_PROVIDER or
        // PROCESSING — an intent that already failed/succeeded/expired/was
        // cancelled or blocked is returned unchanged (FAILED has a legal
        // successor REVERSED, so a bare isTerminal() check would NOT skip a
        // re-delivered failure and would double-compensate).
        if (!intent.state().canTransitionTo(PaymentState.FAILED)) {
            return new Result(intent, true);
        }
        java.util.UUID releaseEntryId = null;
        if (intent.holdId() != null) {
            String walletId = intent.destination().internalWalletId().orElse("external");
            releaseEntryId = ledger.postEntry(intent.internalId(), LedgerPort.EntryType.RELEASE,
                    walletId, intent.amount(), "payment release " + intent.id() + ": " + reason);
            walletHolds.releaseHold(intent.holdId(), intent.internalId());
        }
        intent.markFailed(reason, releaseEntryId, clock.instant());
        payments.save(intent);
        publisher.publish(events.failed(intent, reason, releaseEntryId, clock.instant()));
        return new Result(intent, false);
    }

    private PaymentIntent load(String paymentId) {
        return payments.findById(Objects.requireNonNull(paymentId, "paymentId is required"))
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
    }

    /** @param skipped true when the intent was already terminal */
    public record Result(PaymentIntent intent, boolean skipped) {
    }
}
