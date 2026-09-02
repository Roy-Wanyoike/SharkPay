package com.sharkpay.payouts.config;

import com.sharkpay.payouts.ports.SchedulerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Placeholder production {@link SchedulerPort}: the delayed release is
 * logged, and the polling sweeper (PayoutSweeper + the internal tick
 * endpoint) remains the release safety net, so correctness never depends
 * on a durable timer in this wave.
 *
 * <p><b>Temporal wiring point (ADR 001/002):</b> replace this adapter with
 * a workflow-starter that launches one payout-release workflow per payout
 * with a timer on {@code executeAfter}; the workflow signals exactly the
 * use-cases the sweeper calls today, and the ledger transaction keys are
 * already stable per payout, so in-flight payouts migrate mechanically.
 * The sweep stays as the reconciliation backstop.</p>
 */
public final class LoggingSchedulerPort implements SchedulerPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingSchedulerPort.class);

    @Override
    public void requestRelease(String payoutId, Instant executeAfter) {
        log.info("scheduleRequest payout={} release_at={} (polling sweep is the safety net; "
                + "Temporal timer lands with the workflow layer)", payoutId, executeAfter);
    }

    @Override
    public void cancelRelease(String payoutId) {
        log.info("scheduleCancel payout={}", payoutId);
    }
}
