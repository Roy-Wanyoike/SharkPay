package com.sharkpay.payments.fakes;

import com.sharkpay.payments.ports.PaymentLifecyclePort;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recording {@link PaymentLifecyclePort} fake: captures every lifecycle
 * hand-off so tests can assert the creation prefix started orchestration
 * exactly once per payment. The Temporal implementation starts
 * PaymentWorkflow on task queue "payments" (TestWorkflowEnvironment covers
 * that path in the workflow tests).
 */
public final class RecordingPaymentLifecycle implements PaymentLifecyclePort {

    private final ConcurrentHashMap<String, Integer> starts = new ConcurrentHashMap<>();

    @Override
    public void start(String paymentId) {
        starts.merge(paymentId, 1, Integer::sum);
    }

    /** How many times the lifecycle was started for the payment. */
    public int startsOf(String paymentId) {
        return starts.getOrDefault(paymentId, 0);
    }

    /** All payment ids handed off, in encounter order. */
    public List<String> startedPaymentIds() {
        return List.copyOf(starts.keySet());
    }
}
