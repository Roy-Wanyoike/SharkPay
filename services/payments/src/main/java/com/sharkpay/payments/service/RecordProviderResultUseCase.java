package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.events.PaymentEvents;
import com.sharkpay.payments.ports.EventPublisher;
import com.sharkpay.payments.ports.IdempotencyStore;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.ports.PaymentRepository;
import com.sharkpay.payments.ports.RiskPort;
import com.sharkpay.payments.ports.WalletHoldPort;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider-result application (confirm → capture, the workflow's confirm and
 * capture steps + the internal callback intake): maps the rail-agnostic
 * transfer status onto the payment state machine.
 *
 * <ul>
 *   <li>{@code PROCESSING} → PENDING_PROVIDER → PROCESSING (provider
 *       accepted; no event type in the /v1 catalog);</li>
 *   <li>{@code SUCCEEDED} → the STATE-MACHINES.md §1 guard "SUCCEEDED is
 *       reachable only after risk post-evaluation passed": post-authorization
 *       risk runs first — DENY/REVIEW fails closed into the compensation
 *       path (FAILED, hold released, no capture); ALLOW captures (ledger
 *       {@code CAPTURE} entry + wallet hold capture) → SUCCEEDED +
 *       {@code payments.payment.succeeded.v1};</li>
 *   <li>{@code FAILED}/{@code RETURNED} → compensation (release hold) →
 *       FAILED + {@code payments.payment.failed.v1};</li>
 *   <li>{@code PENDING}/{@code UNKNOWN} → no transition: UNKNOWN is the
 *       provider.go AMBIGUITY CONTRACT — park and keep resolving, never
 *       guess.</li>
 * </ul>
 *
 * <p>Idempotent twice over: a replayed Idempotency-Key returns the original
 * outcome directly, and a second SUCCEEDED result can never re-capture (the
 * state guard + the idempotent ports make double delivery a no-op — ADR 003
 * G2 "no double capture").</p>
 */
public final class RecordProviderResultUseCase {

    private final PaymentRepository payments;
    private final WalletHoldPort walletHolds;
    private final LedgerPort ledger;
    private final RiskPort risk;
    private final IdempotencyStore idempotency;
    private final PaymentEvents events;
    private final EventPublisher publisher;
    private final Clock clock;

    public RecordProviderResultUseCase(PaymentRepository payments, WalletHoldPort walletHolds,
                                       LedgerPort ledger, RiskPort risk,
                                       IdempotencyStore idempotency, PaymentEvents events,
                                       EventPublisher publisher, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.walletHolds = Objects.requireNonNull(walletHolds, "walletHoldPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.risk = Objects.requireNonNull(risk, "riskPort is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "paymentEvents is required");
        this.publisher = Objects.requireNonNull(publisher, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Applies a provider transfer status to the intent.
     *
     * @param idempotencyKey internal caller Idempotency-Key (nullable — the
     *                       state guards alone are already idempotent)
     */
    public Result record(String idempotencyKey, String paymentId, String statusWire) {
        Objects.requireNonNull(statusWire, "status is required");
        Objects.requireNonNull(paymentId, "paymentId is required");
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null
                : idempotencyKey.trim();
        String fingerprint = fingerprint(paymentId, statusWire);
        if (key != null) {
            Optional<IdempotencyStore.StoredRequest> stored =
                    idempotency.find(IdempotencyStore.Scope.PROVIDER_RESULT, key);
            if (stored.isPresent()) {
                if (!stored.get().requestFingerprint().equals(fingerprint)) {
                    throw new com.sharkpay.payments.domain.IdempotencyConflictException(key);
                }
                PaymentIntent original = payments.findById(stored.get().entityId())
                        .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(
                                stored.get().entityId()));
                return new Result(original, true);
            }
        }

        com.sharkpay.payments.ports.ProviderGatewayPort.TransferStatus status;
        try {
            status = com.sharkpay.payments.ports.ProviderGatewayPort.TransferStatus
                    .valueOf(statusWire.trim());
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException("unknown provider status: " + statusWire);
        }
        PaymentIntent intent = load(paymentId);
        switch (status) {
            case PROCESSING -> {
                if (intent.state() == com.sharkpay.payments.domain.PaymentState.PENDING_PROVIDER) {
                    intent.markProcessing(clock.instant());
                    payments.save(intent);
                }
                // already PROCESSING/terminal: idempotent no-op
            }
            case SUCCEEDED -> confirm(intent);
            case FAILED, RETURNED -> {
                String reason = status == com.sharkpay.payments.ports.ProviderGatewayPort.TransferStatus.FAILED
                        ? "provider_failed" : "provider_returned";
                if (intent.state() == com.sharkpay.payments.domain.PaymentState.PENDING_PROVIDER
                        || intent.state() == com.sharkpay.payments.domain.PaymentState.PROCESSING) {
                    compensate(intent, reason);
                }
                // terminal already: idempotent no-op
            }
            case PENDING, UNKNOWN -> {
                // park: no terminal transition off an ambiguous/pending answer
            }
        }
        if (key != null) {
            idempotency.put(IdempotencyStore.Scope.PROVIDER_RESULT, key,
                    new IdempotencyStore.StoredRequest(fingerprint, intent.id()));
        }
        return new Result(intent, false);
    }

    private void confirm(PaymentIntent intent) {
        if (intent.state() == com.sharkpay.payments.domain.PaymentState.SUCCEEDED) {
            return; // no double capture
        }
        if (intent.state() != com.sharkpay.payments.domain.PaymentState.PENDING_PROVIDER
                && intent.state() != com.sharkpay.payments.domain.PaymentState.PROCESSING) {
            throw new com.sharkpay.payments.domain.PaymentStateException(intent.id(),
                    intent.state(), com.sharkpay.payments.domain.PaymentState.SUCCEEDED);
        }
        if (intent.state() == com.sharkpay.payments.domain.PaymentState.PENDING_PROVIDER) {
            intent.markProcessing(clock.instant());
        }
        RiskPort.RiskDecision postRisk = risk.evaluate(new RiskPort.RiskEvaluation(
                intent.principalId(), intent.id(), intent.amount(), intent.rail().wireName(),
                intent.destination().internalWalletId().orElse(null),
                RiskPort.Phase.POST_AUTHORIZATION));
        if (postRisk.decision() != RiskPort.Decision.ALLOW) {
            compensate(intent, "post_authorization_risk: "
                    + String.join("; ", postRisk.reasons()));
            return;
        }
        String walletId = intent.destination().internalWalletId()
                .orElseThrow(() -> new IllegalStateException(
                        "payment " + intent.id() + " has no internal wallet destination"));
        java.util.UUID captureEntryId = ledger.postEntry(intent.internalId(),
                LedgerPort.EntryType.CAPTURE, walletId, intent.amount(),
                "payment capture " + intent.id());
        walletHolds.captureHold(intent.holdId(), intent.amount(), intent.internalId());
        intent.markSucceeded(captureEntryId, clock.instant());
        payments.save(intent);
        publisher.publish(events.succeeded(intent, captureEntryId, clock.instant()));
    }

    private void compensate(PaymentIntent intent, String reason) {
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
    }

    private PaymentIntent load(String paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
    }

    static String fingerprint(String paymentId, String statusWire) {
        return "PROVIDER_RESULT|" + paymentId + "|" + statusWire.trim();
    }

    /**
     * @param intent the intent after applying the result
     * @param replay true when served from the idempotency store
     */
    public record Result(PaymentIntent intent, boolean replay) {
    }
}
