package com.sharkpay.payments.service;

import com.sharkpay.money.Currencies;
import com.sharkpay.money.Money;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.payments.domain.Destination;
import com.sharkpay.payments.domain.FeeSchedules;
import com.sharkpay.payments.domain.IdempotencyConflictException;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.Rail;
import com.sharkpay.payments.domain.RiskReviewException;
import com.sharkpay.payments.domain.UnsupportedCurrencyException;
import com.sharkpay.payments.domain.UnknownWalletException;
import com.sharkpay.payments.events.PaymentEvents;
import com.sharkpay.payments.ports.EventPublisher;
import com.sharkpay.payments.ports.IdempotencyStore;
import com.sharkpay.payments.ports.PaymentLifecyclePort;
import com.sharkpay.payments.ports.PaymentRepository;
import com.sharkpay.payments.ports.Randomness;
import com.sharkpay.payments.ports.RiskPort;
import com.sharkpay.payments.ports.WalletHoldPort;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Create a payment intent (payments.yaml createPayment). The synchronous
 * prefix runs risk → hold → route → initiate (payments.yaml: "Creation runs
 * synchronously through risk evaluation, hold placement and provider
 * hand-off, so the returned intent is already in PENDING_PROVIDER (or a
 * terminal/blocked state)"), then hands the remaining lifecycle (poll /
 * confirm / capture / expiry / compensation) to the Temporal workflow via
 * {@link PaymentLifecyclePort}. The workflow re-runs the same idempotent
 * steps first — safe by construction (ADR 003 G2).
 *
 * <p>Sequence and outcomes:</p>
 * <ol>
 *   <li>idempotency replay: same key + same fingerprint returns the original
 *       intent ({@code replay=true}, no second effect); different payload →
 *       409 {@code idempotency_conflict}. A replay of an intent still in
 *       CREATED (a previous attempt broke mid-prefix, before any state the
 *       contract documents as a create response) re-drives the idempotent
 *       prefix — saga resume — and a replay of an in-flight intent re-fires
 *       the (idempotent) lifecycle hand-off, so no retry can ever observe a
 *       stuck intent (see {@link #create});</li>
 *   <li>currency + fee: unknown currency or a rail/currency with no fee
 *       schedule → 422 {@code unsupported_currency}; fee overflow → 422
 *       {@code money_overflow} (nothing persisted);</li>
 *   <li>destination wallet must exist → 404 {@code not_found} (common.yaml
 *       request-body identifier rule);</li>
 *   <li>risk pre-evaluation runs BEFORE anything is persisted (a REVIEW
 *       rejection must not consume the idempotency key — the caller retries
 *       the same request after the review clears and it re-evaluates):
 *       REVIEW → 422 {@code risk_blocked}, nothing persisted; DENY → intent
 *       persisted CREATED → BLOCKED (201, state machine §1 "risk deny", no
 *       money moved);</li>
 *   <li>hold: ledger HOLD entry + wallet hold → PENDING_PROVIDER +
 *       {@code payments.payment.pending_provider.v1};</li>
 *   <li>provider hand-off: business rejection / no eligible provider →
 *       compensation (release) → FAILED (201 with terminal state);
 *       transient unavailability → stays PENDING_PROVIDER, the workflow
 *       retries initiate;</li>
 *   <li>lifecycle start + return the intent.</li>
 * </ol>
 */
public final class CreatePaymentUseCase {

    /** payments.yaml default: expires_in_seconds defaults to 900. */
    public static final int DEFAULT_EXPIRY_SECONDS = 900;

    private final PaymentRepository payments;
    private final IdempotencyStore idempotency;
    private final RiskPort risk;
    private final WalletHoldPort walletHolds;
    private final PlaceHoldUseCase placeHold;
    private final ProviderHandoffUseCase handoff;
    private final FailPaymentUseCase failPayment;
    private final PaymentLifecyclePort lifecycle;
    private final PaymentEvents events;
    private final EventPublisher publisher;
    private final Randomness randomness;
    private final Clock clock;

    public CreatePaymentUseCase(PaymentRepository payments, IdempotencyStore idempotency,
                                RiskPort risk, WalletHoldPort walletHolds, PlaceHoldUseCase placeHold,
                                ProviderHandoffUseCase handoff, FailPaymentUseCase failPayment,
                                PaymentLifecyclePort lifecycle, PaymentEvents events,
                                EventPublisher publisher, Randomness randomness, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.risk = Objects.requireNonNull(risk, "riskPort is required");
        this.walletHolds = Objects.requireNonNull(walletHolds, "walletHoldPort is required");
        this.placeHold = Objects.requireNonNull(placeHold, "placeHoldUseCase is required");
        this.handoff = Objects.requireNonNull(handoff, "providerHandoffUseCase is required");
        this.failPayment = Objects.requireNonNull(failPayment, "failPaymentUseCase is required");
        this.lifecycle = Objects.requireNonNull(lifecycle, "paymentLifecycle is required");
        this.events = Objects.requireNonNull(events, "paymentEvents is required");
        this.publisher = Objects.requireNonNull(publisher, "eventPublisher is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey      client Idempotency-Key (required, non-blank)
     * @param principalId         owning principal (from the JWT)
     * @param amountMinor         amount to collect, minor units (≥ 1)
     * @param currency            KES USD EUR GBP USDC USDT (case-insensitive)
     * @param destinationWalletId the wallet the funds settle into
     * @param railWire            optional rail hint (wire name)
     * @param metadata            caller metadata (≤ 20 entries)
     * @param expiresInSeconds    TTL 60..86400 (null → 900)
     */
    public Result create(String idempotencyKey, UUID principalId, long amountMinor, String currency,
                         String destinationWalletId, String railWire, Map<String, String> metadata,
                         Integer expiresInSeconds) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(principalId, "principalId is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(destinationWalletId, "destinationWalletId is required");

        String canonicalCurrency = normalizeCurrency(currency);
        Money amount = Money.of(amountMinor, canonicalCurrency);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount_minor must be positive: " + amountMinor);
        }
        Rail rail = resolveRail(railWire, canonicalCurrency);
        Money fee = FeeSchedules.forRailAndCurrency(rail, canonicalCurrency)
                .orElseThrow(() -> new UnsupportedCurrencyException(canonicalCurrency, rail))
                .computeFee(amount); // MoneyOverflowException → 422 money_overflow
        if (!walletHolds.walletExists(destinationWalletId)) {
            throw new UnknownWalletException(destinationWalletId);
        }
        Duration ttl = Duration.ofSeconds(
                expiresInSeconds == null ? DEFAULT_EXPIRY_SECONDS : expiresInSeconds);

        String key = idempotencyKey.trim();
        String fingerprint = fingerprint(principalId, amountMinor, canonicalCurrency,
                destinationWalletId, rail, ttl, metadata);
        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.CREATE_PAYMENT, key);
        if (stored.isPresent()) {
            if (!stored.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(key);
            }
            PaymentIntent original = payments.findById(stored.get().entityId())
                    .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(
                            stored.get().entityId()));
            if (original.state() == com.sharkpay.payments.domain.PaymentState.CREATED) {
                // A previous attempt with this key broke mid-prefix (wallet /
                // ledger / storage outage after the intent was persisted) and
                // left it in CREATED — a state payments.yaml never documents
                // as a create response, and one nothing else repairs (the
                // workflow is only started at the end of the prefix, and
                // expiry only fires from PENDING_PROVIDER). Re-drive the
                // prefix here: every step is idempotent (ledger entries keyed
                // (paymentId, HOLD); the wallet hold keyed by internal id;
                // markPendingProvider guarded by CREATED; handoff replay
                // guarded by providerRef), so the resume can never double-place
                // a hold or double-initiate. Risk is NOT re-run: it already
                // allowed this intent (the persisted CREATED row is the
                // evidence) and a second evaluation would double-count
                // velocity on the risk side — the tier rank therefore falls
                // back to the workflow's own fail-closed value for skipped
                // risk (0).
                return drivePrefix(original, 0, true);
            }
            if (original.state().isSagaLive()) {
                // PENDING_PROVIDER / PROCESSING: the workflow may never have
                // been started (outage between the hold and the hand-off).
                // Re-firing the start is idempotent (in-flight set + Temporal
                // workflow-id dedupe) and guarantees the saga owns the
                // remainder of the lifecycle — polling, expiry, compensation.
                lifecycle.start(original.id());
            }
            return new Result(original, true);
        }

        // risk pre-evaluation runs BEFORE anything is persisted: a REVIEW
        // rejection must not consume the idempotency key — the caller retries
        // the same request once the review clears and it re-evaluates
        RiskPort.RiskDecision decision = risk.evaluate(new RiskPort.RiskEvaluation(
                principalId, "pay_pending_" + key, amount, rail.wireName(), destinationWalletId,
                RiskPort.Phase.PRE_AUTHORIZATION));
        if (decision.decision() == RiskPort.Decision.REVIEW) {
            // fail closed without persisting: the caller retries after review
            throw new RiskReviewException(decision.reasons());
        }

        PaymentIntent intent = PaymentIntent.newIntent(randomness.paymentId(),
                randomness.uuidV7(), principalId, null,
                Destination.internalWallet(destinationWalletId), amount, fee, key, rail,
                clock.instant().plus(ttl), sanitizeMetadata(metadata), clock.instant());
        payments.save(intent);
        // the key is claimed BEFORE any money effect and before the created
        // event: an outage below must never let a retry mint a second intent
        // (a second hold / initiation) for the same logical request — the
        // resume path above repairs this one instead
        idempotency.put(IdempotencyStore.Scope.CREATE_PAYMENT, key,
                new IdempotencyStore.StoredRequest(fingerprint, intent.id()));
        publisher.publish(events.created(intent, clock.instant()));

        if (decision.decision() == RiskPort.Decision.DENY) {
            intent.markBlocked("risk_deny: " + String.join("; ", decision.reasons()),
                    clock.instant());
            payments.save(intent);
            return new Result(intent, false);
        }

        return drivePrefix(intent, decision.tierRank(), false);
    }

    /**
     * Runs the synchronous money-prefix after the intent is persisted (risk
     * already decided): hold → route/initiate → lifecycle start, with the
     * failure outcomes of the class javadoc. Every step is idempotent, so
     * this drives both the fresh creation and the mid-prefix outage resume
     * of a replayed key (ADR 003 G2: same key ⇒ same result, no double
     * effect — also across an outage-retry pair).
     *
     * @param tierRank the routing tier rank (the risk decision's on the fresh
     *                 path; the fail-closed 0 on resume, like the workflow's
     *                 skipped-risk value)
     * @param replay   true when serving a replayed idempotency key
     */
    private Result drivePrefix(PaymentIntent intent, int tierRank, boolean replay) {
        placeHold.place(intent.id());
        PaymentIntent held = payments.findById(intent.id()).orElse(intent);
        try {
            ProviderHandoffUseCase.Result handoffResult =
                    handoff.handoff(intent.id(), tierRank);
            if (handoffResult.type() == ProviderHandoffUseCase.Result.Type.REJECTED
                    || handoffResult.type() == ProviderHandoffUseCase.Result.Type.NO_ROUTE) {
                failPayment.fail(intent.id(), handoffResult.detail());
                return new Result(payments.findById(intent.id()).orElse(held), replay);
            }
        } catch (com.sharkpay.payments.ports.ProviderUnavailableException transientFailure) {
            // stays PENDING_PROVIDER; the workflow's retry policy re-initiates
        }
        lifecycle.start(intent.id());
        return new Result(payments.findById(intent.id()).orElse(held), replay);
    }

    private String normalizeCurrency(String currency) {
        try {
            return Currencies.normalize(currency);
        } catch (com.sharkpay.money.UnknownCurrencyException unknown) {
            throw new UnsupportedCurrencyException(currency);
        }
    }

    /**
     * Rejects null values / blank keys (a null metadata value would poison
     * the immutable domain map and the jsonb column): 400 validation_error.
     */
    private static Map<String, String> sanitizeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("metadata keys must be non-blank strings");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "metadata value for key '" + entry.getKey() + "' must be a string (null is not allowed)");
            }
        }
        return metadata;
    }

    private Rail resolveRail(String railWire, String currency) {
        if (railWire == null || railWire.isBlank()) {
            return FeeSchedules.defaultRailFor(currency)
                    .orElseThrow(() -> new UnsupportedCurrencyException(currency));
        }
        return Rail.fromWire(railWire);
    }

    /** Canonical request fingerprint for conflict detection. */
    static String fingerprint(UUID principalId, long amountMinor, String currency,
                              String destinationWalletId, Rail rail, Duration ttl,
                              Map<String, String> metadata) {
        TreeMap<String, String> sorted = metadata == null ? new TreeMap<>() : new TreeMap<>(metadata);
        return "CREATE_PAYMENT|" + principalId + "|" + amountMinor + "|" + currency + "|"
                + destinationWalletId + "|" + rail.wireName() + "|" + ttl.toSeconds() + "|"
                + sorted;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
    }

    /**
     * @param intent the created (or replayed) intent
     * @param replay true when served from the idempotency store
     */
    public record Result(PaymentIntent intent, boolean replay) {
    }
}
