package com.sharkpay.payments.config;

import com.sharkpay.payments.ports.PaymentLifecyclePort;
import com.sharkpay.payments.workflow.PaymentCommand;
import com.sharkpay.payments.workflow.PaymentWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production {@link PaymentLifecyclePort}: starts (or replays) the
 * {@link PaymentWorkflow} on task queue {@value PaymentWorkflow#TASK_QUEUE}.
 * Fire-and-forget — the REST prefix already advanced the intent; the
 * workflow's idempotent activities pick it up from there. A payment already
 * handed off is never started twice (in-flight set; and Temporal rejects a
 * duplicate workflow-id start while the run is live — which this adapter
 * treats as the success it is, so a start re-fired after a process restart,
 * exactly what the create-replay repair path does, cannot fail a request).
 */
public final class TemporalPaymentLifecycle implements PaymentLifecyclePort {

    private final WorkflowClient client;
    private final Set<String> started = ConcurrentHashMap.newKeySet();

    public TemporalPaymentLifecycle(WorkflowClient client) {
        this.client = Objects.requireNonNull(client, "workflowClient is required");
    }

    @Override
    public void start(String paymentId) {
        Objects.requireNonNull(paymentId, "paymentId is required");
        if (!started.add(paymentId)) {
            return; // already handed off in this process
        }
        PaymentWorkflow workflow = client.newWorkflowStub(PaymentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("payment-" + paymentId)
                        .setTaskQueue(PaymentWorkflow.TASK_QUEUE)
                        .build());
        try {
            WorkflowClient.start(workflow::orchestrate, new PaymentCommand(paymentId));
        } catch (WorkflowExecutionAlreadyStarted alreadyRunning) {
            // idempotent start: a previous process handed this payment off
            // before crashing (the in-memory set is per-process) or the
            // workflow is already serving it — its existence is the goal
        }
    }
}
