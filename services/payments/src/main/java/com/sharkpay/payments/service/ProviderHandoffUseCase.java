package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.ProviderCandidate;
import com.sharkpay.payments.domain.RouterPolicy;
import com.sharkpay.payments.ports.PaymentRepository;
import com.sharkpay.payments.ports.ProviderGatewayPort;
import com.sharkpay.payments.ports.ProviderRejectedException;
import com.sharkpay.payments.ports.ProviderUnavailableException;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Routes the intent to a provider and initiates the transfer (the "route →
 * initiate" steps of the saga). Routing: the {@link RouterPolicy} hard-filters
 * the gateway's candidates (rail/currency/region capability, breaker state,
 * KYC tier, tier limits) and scores the survivors deterministically; the
 * chosen provider is pre-flighted with a {@code quote} and the transfer is
 * initiated with the payment's internal id as the adapter-level idempotency
 * key (SECURITY §4 key chain).
 *
 * <p>Idempotent: if the intent already carries a provider ref the hand-off
 * replays with no second wire call (ADR 003 G2). Transient gateway failures
 * propagate ({@link ProviderUnavailableException}) — the workflow's activity
 * retry policy retries them; the business outcomes come back as data:</p>
 * <ul>
 *   <li>{@code INITIATED} — provider holds the transfer (intent records
 *       provider + providerRef, stays PENDING_PROVIDER);</li>
 *   <li>{@code REJECTED} — definitive rail rejection (compensate);</li>
 *   <li>{@code NO_ROUTE} — no candidate survived the hard filters (fail
 *       closed, compensate);</li>
 *   <li>{@code SKIPPED} — intent is no longer PENDING_PROVIDER (cancelled,
 *       blocked or terminal while the workflow was in flight).</li>
 * </ul>
 */
public final class ProviderHandoffUseCase {

    private final PaymentRepository payments;
    private final ProviderGatewayPort gateway;
    private final RouterPolicy router;
    private final Clock clock;
    private final String defaultRegion;

    public ProviderHandoffUseCase(PaymentRepository payments, ProviderGatewayPort gateway,
                                  RouterPolicy router, Clock clock, String defaultRegion) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.gateway = Objects.requireNonNull(gateway, "providerGateway is required");
        this.router = Objects.requireNonNull(router, "routerPolicy is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.defaultRegion = Objects.requireNonNull(defaultRegion, "defaultRegion is required");
    }

    /** Routes + initiates (or replays) the provider transfer for the intent. */
    public Result handoff(String paymentId, int tierRank) {
        PaymentIntent intent = load(paymentId);
        // state guard FIRST: a cancelled/blocked/terminal intent must report
        // SKIPPED even when it already carries a provider ref (the replay
        // check below must never shadow the state machine)
        if (intent.state() != com.sharkpay.payments.domain.PaymentState.PENDING_PROVIDER
                && intent.state() != com.sharkpay.payments.domain.PaymentState.PROCESSING) {
            return new Result(Result.Type.SKIPPED, intent, "state=" + intent.state().wireName());
        }
        if (intent.providerRef() != null) {
            return new Result(Result.Type.INITIATED, intent, "replay");
        }
        List<ProviderCandidate> candidates = gateway.candidates().stream()
                .map(ProviderHandoffUseCase::toDomain)
                .toList();
        String walletId = intent.destination().internalWalletId().orElse("external");
        return router.select(intent.amount().currency(), intent.rail(), defaultRegion,
                        intent.amount().amountMinor(), tierRank, candidates)
                .map(candidate -> initiateAt(intent, candidate, walletId))
                .orElseGet(() -> new Result(Result.Type.NO_ROUTE, intent,
                        "no_eligible_provider currency=" + intent.amount().currency()
                                + " rail=" + intent.rail().wireName() + " region=" + defaultRegion));
    }

    private Result initiateAt(PaymentIntent intent, ProviderCandidate candidate, String walletId) {
        // pre-flight: can this provider move this amount over this rail?
        try {
            gateway.quote(new ProviderGatewayPort.QuoteRequest(intent.amount().amountMinor(),
                    intent.amount().currency(), intent.rail().wireName(), walletId));
        } catch (ProviderRejectedException unservable) {
            // provider cannot serve the movement at all — same fail-closed
            // outcome as the router's hard filters
            return new Result(Result.Type.NO_ROUTE, intent, unservable.getMessage());
        }
        try {
            ProviderGatewayPort.ProviderRef ref = gateway.initiate(
                    new ProviderGatewayPort.InitiateRequest(intent.internalId().toString(),
                            intent.amount().amountMinor(), intent.amount().currency(),
                            intent.rail().wireName(), walletId, intent.metadata()));
            intent.recordProviderHandoff(candidate.providerId(), ref.ref(), clock.instant());
            payments.save(intent);
            return new Result(Result.Type.INITIATED, intent, candidate.providerId());
        } catch (ProviderRejectedException rejected) {
            return new Result(Result.Type.REJECTED, intent, rejected.getMessage());
        }
    }

    private static ProviderCandidate toDomain(ProviderGatewayPort.ProviderCandidateView view) {
        return new ProviderCandidate(view.providerId(), Set.copyOf(view.rails()),
                Set.copyOf(view.currencies()), Set.copyOf(view.regions()), view.costBps(),
                view.p99Millis(), view.successRateBps(), view.breakerOpen(), view.minTierRank(),
                view.minTxnMinor(), view.maxTxnMinor());
    }

    private PaymentIntent load(String paymentId) {
        return payments.findById(Objects.requireNonNull(paymentId, "paymentId is required"))
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
    }

    /** The hand-off outcome (data, not exceptions — activities stay retryable). */
    public record Result(Type type, PaymentIntent intent, String detail) {

        public enum Type {
            INITIATED, REJECTED, NO_ROUTE, SKIPPED
        }
    }
}
