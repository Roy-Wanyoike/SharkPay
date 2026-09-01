package com.sharkpay.payments.workflow;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.ProviderGatewayPort;
import com.sharkpay.payments.ports.ProviderUnavailableException;
import com.sharkpay.payments.service.EvaluateRiskUseCase;
import com.sharkpay.payments.service.ExpirePaymentUseCase;
import com.sharkpay.payments.service.FailPaymentUseCase;
import com.sharkpay.payments.service.GetPaymentUseCase;
import com.sharkpay.payments.service.PlaceHoldUseCase;
import com.sharkpay.payments.service.ProviderHandoffUseCase;
import com.sharkpay.payments.service.RecordProviderResultUseCase;
import io.temporal.failure.ApplicationFailure;

import java.util.Objects;

/**
 * Activity implementations: thin idempotent adapters over the hexagon's
 * use-cases. ALL money arithmetic, port calls and persistence happen here
 * (application layer) — the workflow only sequences. Business outcomes map
 * to Temporal's failure taxonomy:
 *
 * <ul>
 *   <li>{@code ProviderRejected} / {@code NoRoute} — non-retryable
 *       ApplicationFailures (compensate now);</li>
 *   <li>{@code ProviderUnavailable} — retryable (the activity retry policy
 *       retries it, then the workflow compensates);</li>
 *   <li>everything else — retryable infrastructure failure.</li>
 * </ul>
 */
// The @ActivityInterface lives on PaymentActivities (Temporal canonical form:
// interface carries the annotation); the implementation class stays plain.
public final class PaymentActivitiesImpl implements PaymentActivities {

    /** Non-retryable failure type: definitive rail rejection. */
    static final String TYPE_PROVIDER_REJECTED = "ProviderRejected";
    /** Non-retryable failure type: no candidate survived the hard filters. */
    static final String TYPE_NO_ROUTE = "NoRoute";
    /** Retryable failure type: transient provider unavailability. */
    static final String TYPE_PROVIDER_UNAVAILABLE = "ProviderUnavailable";

    private final EvaluateRiskUseCase evaluateRisk;
    private final PlaceHoldUseCase placeHold;
    private final ProviderHandoffUseCase handoff;
    private final RecordProviderResultUseCase recordResult;
    private final FailPaymentUseCase failPayment;
    private final ExpirePaymentUseCase expirePayment;
    private final GetPaymentUseCase getPayment;
    private final ProviderGatewayPort gateway;
    private final long pollIntervalMs;

    public PaymentActivitiesImpl(EvaluateRiskUseCase evaluateRisk, PlaceHoldUseCase placeHold,
                                 ProviderHandoffUseCase handoff,
                                 RecordProviderResultUseCase recordResult,
                                 FailPaymentUseCase failPayment,
                                 ExpirePaymentUseCase expirePayment,
                                 GetPaymentUseCase getPayment, ProviderGatewayPort gateway,
                                 long pollIntervalMs) {
        this.evaluateRisk = Objects.requireNonNull(evaluateRisk, "evaluateRiskUseCase is required");
        this.placeHold = Objects.requireNonNull(placeHold, "placeHoldUseCase is required");
        this.handoff = Objects.requireNonNull(handoff, "providerHandoffUseCase is required");
        this.recordResult = Objects.requireNonNull(recordResult, "recordProviderResultUseCase is required");
        this.failPayment = Objects.requireNonNull(failPayment, "failPaymentUseCase is required");
        this.expirePayment = Objects.requireNonNull(expirePayment, "expirePaymentUseCase is required");
        this.getPayment = Objects.requireNonNull(getPayment, "getPaymentUseCase is required");
        this.gateway = Objects.requireNonNull(gateway, "providerGateway is required");
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public PaymentSnapshot loadSnapshot(String paymentId) {
        PaymentIntent intent = getPayment.get(paymentId);
        return new PaymentSnapshot(intent.id(), intent.state().wireName(),
                intent.expiresAt().toEpochMilli(), pollIntervalMs, intent.provider(),
                intent.providerRef());
    }

    @Override
    public RiskResult evaluateRisk(String paymentId) {
        EvaluateRiskUseCase.Result result = evaluateRisk.evaluate(paymentId);
        if (result.skipped()) {
            return new RiskResult(null, 0, true);
        }
        return new RiskResult(result.decision().decision().name(), result.decision().tierRank(),
                false);
    }

    @Override
    public HoldResult placeHold(String paymentId) {
        PlaceHoldUseCase.Result result = placeHold.place(paymentId);
        return new HoldResult(result.holdId(), result.skipped());
    }

    @Override
    public String routeAndInitiate(String paymentId, int tierRank) {
        try {
            ProviderHandoffUseCase.Result result = handoff.handoff(paymentId, tierRank);
            return switch (result.type()) {
                case INITIATED -> "INITIATED";
                case SKIPPED -> "SKIPPED";
                case REJECTED -> throw ApplicationFailure.newNonRetryableFailure(
                        result.detail(), TYPE_PROVIDER_REJECTED);
                case NO_ROUTE -> throw ApplicationFailure.newNonRetryableFailure(
                        result.detail(), TYPE_NO_ROUTE);
            };
        } catch (ProviderUnavailableException unavailable) {
            throw ApplicationFailure.newFailure(unavailable.getMessage(),
                    TYPE_PROVIDER_UNAVAILABLE);
        }
    }

    @Override
    public PollResult pollProvider(String paymentId) {
        PaymentIntent intent = getPayment.get(paymentId);
        if (intent.providerRef() == null
                || (intent.state() != PaymentState.PENDING_PROVIDER
                        && intent.state() != PaymentState.PROCESSING)) {
            // nothing in flight to poll (blocked/cancelled/terminal, or the
            // initiate step has not run yet) — report the intent's own state
            return new PollResult("UNKNOWN", intent.state().wireName());
        }
        ProviderGatewayPort.TransferStatus status = gateway.poll(
                new ProviderGatewayPort.ProviderRef(intent.provider(), intent.providerRef()));
        return new PollResult(status.name(), intent.state().wireName());
    }

    @Override
    public void confirmProcessing(String paymentId) {
        recordResult.record(null, paymentId,
                ProviderGatewayPort.TransferStatus.PROCESSING.name());
    }

    @Override
    public PaymentOutcome captureAndSettle(String paymentId) {
        recordResult.record(null, paymentId,
                ProviderGatewayPort.TransferStatus.SUCCEEDED.name());
        return outcome(paymentId);
    }

    @Override
    public PaymentOutcome failPayment(String paymentId, String reason) {
        failPayment.fail(paymentId, reason);
        return outcome(paymentId);
    }

    @Override
    public PaymentOutcome expirePayment(String paymentId) {
        expirePayment.expire(paymentId);
        return outcome(paymentId);
    }

    @Override
    public PaymentOutcome outcome(String paymentId) {
        PaymentIntent intent = getPayment.get(paymentId);
        return new PaymentOutcome(intent.id(), intent.state().wireName(),
                intent.failureReason());
    }
}
