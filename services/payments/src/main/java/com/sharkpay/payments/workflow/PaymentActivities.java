package com.sharkpay.payments.workflow;

/**
 * Activity boundary of the payment saga: one method per use-case step, all
 * idempotent, all primitive/record-typed (Temporal serialisation). Activity
 * implementations live in {@link PaymentActivitiesImpl} (application layer —
 * all money math happens there, never in workflow code).
 *
 * <p>Failure contract:</p>
 * <ul>
 *   <li>{@link #routeAndInitiate} throws non-retryable
 *       {@code ApplicationFailure} (type {@code ProviderRejected} /
 *       {@code NoRoute}) for definitive business rejections — the workflow
 *       compensates immediately; retryable failures (type
 *       {@code ProviderUnavailable}) are retried by the activity retry
 *       policy, then compensated;</li>
 *   <li>{@link #pollProvider} transient failures are retried per the retry
 *       policy; the workflow keeps polling until the expiry deadline;</li>
 *   <li>money-safety activities ({@code captureAndSettle},
 *       {@code failPayment}, {@code expirePayment}) retry generously — they
 *       must eventually run exactly once (ports are idempotent, so retries
 *       are safe).</li>
 * </ul>
 */
@io.temporal.activity.ActivityInterface
public interface PaymentActivities {

    /** Loads the orchestration snapshot (state, deadlines, refs). */
    PaymentSnapshot loadSnapshot(String paymentId);

    /**
     * Pre-authorization risk evaluation; applies BLOCKED on deny (idempotent
     * no-op when the intent is past CREATED).
     *
     * @return decision wire name (ALLOW/DENY/REVIEW), the KYC tier rank for
     *         routing, and whether evaluation was skipped
     */
    RiskResult evaluateRisk(String paymentId);

    /** Places the hold and moves to PENDING_PROVIDER (idempotent). */
    HoldResult placeHold(String paymentId);

    /**
     * Routes and initiates the provider transfer (idempotent).
     *
     * @return {@code INITIATED} or {@code SKIPPED} (intent no longer
     *         PENDING_PROVIDER)
     * @throws io.temporal.failure.ApplicationFailure non-retryable for
     *         business rejection / no eligible provider; retryable for
     *         transient unavailability
     */
    String routeAndInitiate(String paymentId, int tierRank);

    /**
     * Polls the provider for the transfer status.
     *
     * @return the rail status wire name + the intent's current state
     */
    PollResult pollProvider(String paymentId);

    /** PENDING_PROVIDER → PROCESSING (idempotent no-op otherwise). */
    void confirmProcessing(String paymentId);

    /**
     * Post-authorization risk + capture (PROCESSING → SUCCEEDED) or
     * compensation (FAILED). Idempotent.
     */
    PaymentOutcome captureAndSettle(String paymentId);

    /** Compensation: release hold → FAILED with the reason. Idempotent. */
    PaymentOutcome failPayment(String paymentId, String reason);

    /** Expiry: release hold → EXPIRED (only from PENDING_PROVIDER). */
    PaymentOutcome expirePayment(String paymentId);

    /** Current terminal state as an outcome (terminal intents return as-is). */
    PaymentOutcome outcome(String paymentId);

    // ── activity value types (plain, serializable) ────────────────────────

    /**
     * @param paymentId              public intent id
     * @param state                  current state wire name
     * @param expiryDeadlineEpochMs  TTL deadline (workflow timer input)
     * @param pollIntervalMs         provider poll interval (workflow sleep input)
     */
    record PaymentSnapshot(String paymentId, String state, long expiryDeadlineEpochMs,
                           long pollIntervalMs, String provider, String providerRef) {
    }

    /** @param decision ALLOW / DENY / REVIEW wire name; null when skipped */
    record RiskResult(String decision, int tierRank, boolean skipped) {
    }

    /** @param holdId wallet-side hold id; null when placement was skipped */
    record HoldResult(String holdId, boolean skipped) {
    }

    /** @param transferStatus rail status wire name; @param paymentState current intent state */
    record PollResult(String transferStatus, String paymentState) {
    }
}
