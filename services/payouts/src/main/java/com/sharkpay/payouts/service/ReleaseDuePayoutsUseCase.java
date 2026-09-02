package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.BackoffPolicy;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.events.PayoutEvents;
import com.sharkpay.payouts.ports.EventPublisher;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.PayoutRepository;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.ports.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The payout scheduler use-case (clock-driven batch release): payouts with
 * an {@code executeAfter} timestamp that has passed are released in batches
 * — at most {@code batchSize} per tick, execute-after ascending — by
 * submitting each one to its rail via the provider gateway.
 *
 * <p>Submission failures back off with bounded exponential growth plus
 * jitter ({@link BackoffPolicy}); after {@code maxAttempts} total attempts
 * the payout terminates FAILED (never retried again — no infinite retry)
 * and the failed event doubles as the ops alert.</p>
 */
public final class ReleaseDuePayoutsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReleaseDuePayoutsUseCase.class);

    /** Reason recorded when the retry budget is exhausted (ops alert). */
    public static final String MAX_ATTEMPTS_REASON = "max_submit_retries_exceeded";

    private final PayoutRepository payouts;
    private final ProviderGatewayPort gateway;
    private final LedgerPort ledger;
    private final EventPublisher events;
    private final BackoffPolicy backoff;
    private final Randomness randomness;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public ReleaseDuePayoutsUseCase(PayoutRepository payouts, ProviderGatewayPort gateway,
                                    LedgerPort ledger, EventPublisher events,
                                    BackoffPolicy backoff, Randomness randomness, Clock clock,
                                    int batchSize, int maxAttempts) {
        this.payouts = Objects.requireNonNull(payouts, "payoutRepository is required");
        this.gateway = Objects.requireNonNull(gateway, "providerGatewayPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.backoff = Objects.requireNonNull(backoff, "backoffPolicy is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("max attempts must be positive: " + maxAttempts);
        }
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** Releases one due batch. Idempotent and safe to tick concurrently. */
    public Report releaseDue() {
        Instant now = clock.instant();
        List<Payout> due = payouts.findDueForRelease(now, batchSize);
        int submitted = 0;
        int retried = 0;
        int failedTerminal = 0;
        for (Payout payout : due) {
            if (payout.attempts() >= maxAttempts) {
                // defensive: an attempt counter at the bound (e.g. after a
                // crash between failure and save) terminates now
                failedTerminal += failTerminally(payout, now);
                continue;
            }
            try {
                ProviderGatewayPort.ProviderRef ref = gateway.initiate(
                        new ProviderGatewayPort.InitiateSubmission(PayoutMoney.submitKey(payout),
                                payout.id(), payout.rail().wireName(), payout.destination(),
                                payout.amount().amountMinor(), payout.amount().currency(),
                                payout.amount().exponent(), payout.metadata()));
                payout.markSubmitted(ref.provider() + ":" + ref.ref(), now);
                payouts.save(payout);
                events.publish(PayoutEvents.processing(payout, now));
                submitted++;
            } catch (RuntimeException submissionFailure) {
                log.warn("payout {} submission attempt {} failed: {}", payout.id(),
                        payout.attempts() + 1, submissionFailure.getMessage());
                if (payout.attempts() + 1 >= maxAttempts) {
                    // retry budget exhausted: terminal failure, hold released,
                    // failed event = the ops alert (exactly one terminal event)
                    failedTerminal += failTerminally(payout, now);
                } else {
                    java.time.Duration delay = backoff.nextBackoff(payout.attempts(), randomness);
                    payout.recordSubmitFailure(now.plus(delay), now);
                    payouts.save(payout);
                    retried++;
                }
            }
        }
        return new Report(due.size(), submitted, retried, failedTerminal);
    }

    private int failTerminally(Payout payout, Instant now) {
        HoldReleaser.release(ledger, payout, "payout failed: " + MAX_ATTEMPTS_REASON);
        payout.markFailed(MAX_ATTEMPTS_REASON, now);
        payouts.save(payout);
        events.publish(PayoutEvents.failed(payout, now));
        log.error("OPS-ALERT payout {} exhausted {} submit attempts → FAILED", payout.id(),
                Math.max(payout.attempts(), maxAttempts));
        return 1;
    }

    /** One release tick: considered / submitted / parked-for-retry / failed. */
    public record Report(int considered, int submitted, int retried, int failedTerminal) {
    }
}
