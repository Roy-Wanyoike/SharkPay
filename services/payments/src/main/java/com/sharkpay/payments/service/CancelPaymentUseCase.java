package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.IdempotencyConflictException;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.ports.IdempotencyStore;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.ports.PaymentRepository;
import com.sharkpay.payments.ports.WalletHoldPort;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Cancel an unconfirmed intent (payments.yaml cancelPayment): legal from
 * CREATED or PENDING_PROVIDER only — a confirmed, terminal or blocked intent
 * is a 409 {@code state_conflict} (use reversal flows instead). Any active
 * hold is released (wallet + ledger RELEASE entry), no event type exists for
 * CANCELLED in the /v1 catalog (audited in transitions only).
 *
 * <p>Idempotent: same Idempotency-Key replays the original outcome with no
 * second release (ADR 003 G2).</p>
 */
public final class CancelPaymentUseCase {

    private final PaymentRepository payments;
    private final WalletHoldPort walletHolds;
    private final LedgerPort ledger;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    public CancelPaymentUseCase(PaymentRepository payments, WalletHoldPort walletHolds,
                                LedgerPort ledger, IdempotencyStore idempotency, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.walletHolds = Objects.requireNonNull(walletHolds, "walletHoldPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Cancels (or replays the cancellation of) the intent. */
    public Result cancel(String idempotencyKey, String paymentId) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(paymentId, "paymentId is required");
        String key = idempotencyKey.trim();
        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.CANCEL_PAYMENT, key);
        if (stored.isPresent()) {
            if (!stored.get().requestFingerprint().equals(fingerprint(paymentId))) {
                throw new IdempotencyConflictException(key);
            }
            PaymentIntent original = payments.findById(stored.get().entityId())
                    .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(
                            stored.get().entityId()));
            return new Result(original, true);
        }
        PaymentIntent intent = payments.findById(paymentId)
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
        if (!intent.state().isCancellable()) {
            throw new com.sharkpay.payments.domain.PaymentStateException(intent.id(),
                    intent.state(), com.sharkpay.payments.domain.PaymentState.CANCELLED);
        }
        java.util.UUID releaseEntryId = null;
        if (intent.holdId() != null) {
            String walletId = intent.destination().internalWalletId().orElse("external");
            releaseEntryId = ledger.postEntry(intent.internalId(), LedgerPort.EntryType.RELEASE,
                    walletId, intent.amount(), "payment cancel release " + intent.id());
            walletHolds.releaseHold(intent.holdId(), intent.internalId());
        }
        intent.markCancelled(releaseEntryId, clock.instant());
        payments.save(intent);
        idempotency.put(IdempotencyStore.Scope.CANCEL_PAYMENT, key,
                new IdempotencyStore.StoredRequest(fingerprint(paymentId), intent.id()));
        return new Result(intent, false);
    }

    static String fingerprint(String paymentId) {
        return "CANCEL_PAYMENT|" + paymentId;
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
