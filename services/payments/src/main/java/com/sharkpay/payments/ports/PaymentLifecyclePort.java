package com.sharkpay.payments.ports;

/**
 * Lifecycle hand-off port: after the synchronous creation prefix
 * (risk → hold → route → initiate), the intent's remaining lifecycle
 * (provider polling, capture, expiry timer, compensation) is driven by a
 * Temporal workflow.
 *
 * <p>Production implementation starts {@code PaymentWorkflow} on task queue
 * {@code payments}; the test fake drives the same use-cases directly. The
 * workflow is safe to start even when the prefix already advanced the
 * intent: every activity is idempotent (ADR 003 G2).</p>
 */
public interface PaymentLifecyclePort {

    /**
     * Starts (or no-ops if already started) the lifecycle orchestration for
     * {@code paymentId}.
     */
    void start(String paymentId);
}
