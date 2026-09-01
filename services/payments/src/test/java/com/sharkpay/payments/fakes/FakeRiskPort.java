package com.sharkpay.payments.fakes;

import com.sharkpay.payments.ports.RiskPort;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scripted {@link RiskPort} fake: serves decisions from a queue (default
 * ALLOW with the given KYC tier rank) and records every evaluation so tests
 * can pin the risk phases that ran and their arguments. Executable spec for
 * the real risk REST adapter.
 */
public final class FakeRiskPort implements RiskPort {

    private final Queue<RiskDecision> scripted = new ConcurrentLinkedQueue<>();
    private final List<RiskEvaluation> evaluations = new CopyOnWriteArrayList<>();
    private final AtomicLong deniedCount = new AtomicLong();
    private RiskDecision defaultDecision = new RiskDecision(Decision.ALLOW, List.of(), 2);

    /** Queues the next decision (consumed before the default). */
    public FakeRiskPort next(RiskDecision decision) {
        scripted.add(decision);
        return this;
    }

    /** Sets the decision served when the queue is empty. */
    public FakeRiskPort byDefault(RiskDecision decision) {
        this.defaultDecision = decision;
        return this;
    }

    @Override
    public RiskDecision evaluate(RiskEvaluation evaluation) {
        evaluations.add(evaluation);
        RiskDecision decision = scripted.poll();
        if (decision == null) {
            decision = defaultDecision;
        }
        if (decision.decision() != Decision.ALLOW) {
            deniedCount.incrementAndGet();
        }
        return decision;
    }

    /** Every evaluation the service made, in call order. */
    public List<RiskEvaluation> evaluations() {
        return List.copyOf(evaluations);
    }

    /** The evaluation with this payment id, when present. */
    public RiskEvaluation evaluationOf(String paymentId) {
        return evaluations.stream()
                .filter(evaluation -> paymentId.equals(evaluation.paymentId()))
                .findFirst().orElse(null);
    }

    /** Number of non-ALLOW decisions served. */
    public long deniedCount() {
        return deniedCount.get();
    }

    /** Convenience: an ALLOW decision at the given tier rank. */
    public static RiskDecision allow(int tierRank) {
        return new RiskDecision(Decision.ALLOW, List.of(), tierRank);
    }

    /** Convenience: a DENY decision with reasons. */
    public static RiskDecision deny(String... reasons) {
        return new RiskDecision(Decision.DENY, List.of(reasons), 2);
    }

    /** Convenience: a REVIEW decision with reasons. */
    public static RiskDecision review(String... reasons) {
        return new RiskDecision(Decision.REVIEW, List.of(reasons), 2);
    }

    /** Convenience: an ALLOW decision with a null (unknown) tier. */
    public static RiskDecision allowUnknownTier() {
        return new RiskDecision(Decision.ALLOW, List.of(), null);
    }
}
