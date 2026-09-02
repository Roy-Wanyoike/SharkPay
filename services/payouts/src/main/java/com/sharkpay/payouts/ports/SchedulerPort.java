package com.sharkpay.payouts.ports;

import java.time.Instant;
import java.util.Objects;

/**
 * Outbound scheduling port: the seam where a payout's delayed release is
 * registered. In this wave the production adapter only logs the request —
 * the polling sweeper ({@code PayoutSweeper} @Scheduled tick +
 * {@code POST /internal/payouts/scheduler/tick}) is the safety net that
 * releases every due payout regardless, so correctness never depends on a
 * durable timer.
 *
 * <p><b>Temporal wiring point:</b> when the workflow layer lands, this
 * adapter becomes a {@code WorkflowClient} start (one payout-release
 * workflow per payout, timer on {@code executeAfter}); the sweeper stays as
 * the reconciliation backstop. The domain and ledger keys are already
 * stable per payout, so the migration is mechanical.</p>
 */
public interface SchedulerPort {

    /** Requests a release wake-up for {@code payoutId} at {@code executeAfter}. */
    void requestRelease(String payoutId, Instant executeAfter);

    /** Cancels a previously requested wake-up (payout left PENDING_RISK). */
    default void cancelRelease(String payoutId) {
        // polling sweep makes cancellation optional; default no-op
    }

    /** Utility for null-checking implementations. */
    static Instant requirePayout(String payoutId, Instant executeAfter) {
        Objects.requireNonNull(payoutId, "payoutId is required");
        return Objects.requireNonNull(executeAfter, "executeAfter is required");
    }
}
