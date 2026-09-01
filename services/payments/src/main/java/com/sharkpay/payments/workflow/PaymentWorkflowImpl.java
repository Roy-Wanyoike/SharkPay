package com.sharkpay.payments.workflow;

import com.sharkpay.payments.domain.PaymentState;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.Set;

/**
 * Payment lifecycle saga (risk → hold → route → initiate → confirm →
 * capture, with compensation on every failure path, the expiry timer and
 * provider polling).
 *
 * <p><b>Determinism rules honoured:</b> the workflow only sequences
 * activities, compares strings/booleans and sleeps (Workflow.sleep /
 * Workflow.currentTimeMillis). No money arithmetic, no port calls, no
 * wall-clock, no random — all of that lives in the activities. Every
 * activity is idempotent, so the synchronous REST creation prefix, activity
 * retries and workflow replays are all safe (ADR 003 G2).</p>
 *
 * <p><b>Compensation is ALWAYS release/reversal</b> (never in-place
 * mutation): every failure path routes through the {@code failPayment} /
 * {@code expirePayment} activities, which release the hold (wallet + ledger
 * RELEASE entry) exactly once before landing in a terminal state.</p>
 *
 * <p>Three activity stubs with distinct retry policies (registered on the
 * same interface): route/initiate — 4 attempts for transient provider
 * unavailability; poll — 3 attempts per cycle (the loop re-polls); the
 * money-safety activities — 5 generous attempts (idempotent effects, must
 * eventually run). Business rejections are non-retryable by type.</p>
 */
public final class PaymentWorkflowImpl implements PaymentWorkflow {

    private static final int INITIATE_MAX_ATTEMPTS = 4;
    private static final int POLL_MAX_ATTEMPTS = 3;
    private static final int SETTLE_MAX_ATTEMPTS = 5;

    /** Non-retryable business failure types (see PaymentActivitiesImpl). */
    private static final Set<String> NON_RETRYABLE = Set.of("ProviderRejected", "NoRoute");

    private final PaymentActivities initiate;
    private final PaymentActivities poller;
    private final PaymentActivities settle;

    public PaymentWorkflowImpl() {
        this.initiate = Workflow.newActivityStub(PaymentActivities.class, options(
                INITIATE_MAX_ATTEMPTS, Duration.ofSeconds(15), Duration.ofSeconds(30)));
        this.poller = Workflow.newActivityStub(PaymentActivities.class, options(
                POLL_MAX_ATTEMPTS, Duration.ofSeconds(10), Duration.ofSeconds(20)));
        this.settle = Workflow.newActivityStub(PaymentActivities.class, options(
                SETTLE_MAX_ATTEMPTS, Duration.ofSeconds(10), Duration.ofSeconds(60)));
    }

    private static ActivityOptions options(int maxAttempts, Duration startToClose,
                                           Duration scheduleToClose) {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(startToClose)
                .setScheduleToCloseTimeout(scheduleToClose)
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(maxAttempts)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofSeconds(5))
                        .setDoNotRetry(NON_RETRYABLE.toArray(new String[0]))
                        .build())
                .build();
    }

    @Override
    public PaymentOutcome orchestrate(PaymentCommand command) {
        PaymentActivities.PaymentSnapshot snapshot = settle.loadSnapshot(command.paymentId());
        if (isTerminal(snapshot.state())) {
            return settle.outcome(command.paymentId());
        }

        // 1. risk (idempotent; applies BLOCKED on deny)
        PaymentActivities.RiskResult risk = settle.evaluateRisk(command.paymentId());
        if (risk.decision() != null && !"ALLOW".equals(risk.decision())) {
            // DENY / REVIEW — the activity already applied BLOCKED (fail
            // closed); terminal for this run
            return settle.outcome(command.paymentId());
        }
        // risk.decision() == null ⇒ evaluation was skipped: the synchronous
        // REST creation prefix (or a previous run) already moved this intent
        // past CREATED. Fall through — hold/route/initiate are idempotent
        // replays in that case, and the poll/capture/expiry loop below is
        // exactly the part of the lifecycle the prefix did NOT run.

        // 2. hold (idempotent; CREATED → PENDING_PROVIDER)
        settle.placeHold(command.paymentId());

        // 3. route + initiate (idempotent; retries transient unavailability)
        try {
            String handoff = initiate.routeAndInitiate(command.paymentId(), risk.tierRank());
            if ("SKIPPED".equals(handoff)) {
                return settle.outcome(command.paymentId());
            }
        } catch (ActivityFailure failure) {
            // definitive rejection / retries exhausted: compensate + fail
            settle.failPayment(command.paymentId(), failureReason(failure));
            return settle.outcome(command.paymentId());
        }

        // 4. poll → confirm → capture, bounded by the expiry timer
        return awaitTerminalState(command, snapshot);
    }

    private PaymentOutcome awaitTerminalState(PaymentCommand command,
                                              PaymentActivities.PaymentSnapshot snapshot) {
        String paymentId = command.paymentId();
        long deadlineMs = snapshot.expiryDeadlineEpochMs();
        long pollIntervalMs = Math.max(1, snapshot.pollIntervalMs());
        while (true) {
            PaymentActivities.PollResult poll = null;
            try {
                poll = poller.pollProvider(paymentId);
            } catch (ActivityFailure transientPollFailure) {
                // a poll cycle that exhausted its retries: keep resolving
                // until the deadline — never fail a payment off a read error
            }
            if (poll != null) {
                switch (poll.transferStatus()) {
                    case "PROCESSING" -> settle.confirmProcessing(paymentId);
                    case "SUCCEEDED" -> {
                        // confirm + post-risk + capture (or compensation)
                        try {
                            return settle.captureAndSettle(paymentId);
                        } catch (ActivityFailure raced) {
                            // the intent reached another terminal state
                            // concurrently (expiry/cancel/fail); the outcome
                            // activity reports the winning state
                            return settle.outcome(paymentId);
                        }
                    }
                    case "FAILED" -> {
                        return settle.failPayment(paymentId, "provider_failed");
                    }
                    case "RETURNED" -> {
                        return settle.failPayment(paymentId, "provider_returned");
                    }
                    default -> {
                        // PENDING / UNKNOWN: park per the provider.go
                        // AMBIGUITY CONTRACT — keep resolving, never guess
                    }
                }
                if (isTerminal(poll.paymentState())) {
                    // cancelled/blocked externally while polling
                    return settle.outcome(paymentId);
                }
            }

            // expiry timer: sleep at most one poll interval; when the TTL is
            // spent and the intent is still unconfirmed, expire it (the
            // activity releases the hold exactly once)
            long remainingMs = deadlineMs - Workflow.currentTimeMillis();
            if (remainingMs <= 0) {
                settle.expirePayment(paymentId);
                return settle.outcome(paymentId);
            }
            Workflow.sleep(Duration.ofMillis(Math.min(pollIntervalMs, remainingMs)));
        }
    }

    /**
     * Unwraps the activity failure's cause: a non-retryable
     * ApplicationFailure carries the business reason; anything else was a
     * transient failure whose retries the policy exhausted.
     */
    private static String failureReason(ActivityFailure failure) {
        if (failure.getCause() instanceof ApplicationFailure applicationFailure
                && applicationFailure.isNonRetryable()) {
            return applicationFailure.getMessage();
        }
        return "provider_unavailable";
    }

    private static boolean isTerminal(String stateWire) {
        try {
            return PaymentState.fromWire(stateWire).isTerminal();
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
