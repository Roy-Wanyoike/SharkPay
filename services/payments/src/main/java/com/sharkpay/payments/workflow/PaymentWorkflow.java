package com.sharkpay.payments.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Payment lifecycle orchestration (WP-5 Temporal saga): risk → hold → route
 * → initiate → confirm → capture, with compensation on every failure path,
 * the expiry timer and provider polling. Registered on task queue
 * {@value #TASK_QUEUE} by {@code TemporalWorkerConfig}.
 *
 * <p>The workflow is written against the idempotent use-cases via
 * {@link PaymentActivities}: every step is safe to re-run (the synchronous
 * REST creation prefix may already have advanced the intent; crashes and
 * activity at-least-once delivery replay too). Workflow code contains no
 * money arithmetic — activities do it (ADR 003 / BACKEND-DESIGN §6
 * determinism rule).</p>
 */
@WorkflowInterface
public interface PaymentWorkflow {

    /** Per-runtime task queue (ADR 002: one implementation per workflow). */
    String TASK_QUEUE = "payments";

    /**
     * Drives the intent to a terminal state and reports it. Starts from
     * wherever the intent currently is (activities are idempotent no-ops for
     * already-completed steps).
     */
    @WorkflowMethod
    PaymentOutcome orchestrate(PaymentCommand command);
}
