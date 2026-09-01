package com.sharkpay.risk.domain;

import com.sharkpay.risk.ports.VelocityCounterStore;

/**
 * A single risk rule. Implementations are stateless value objects: all
 * tunables come from the {@link RuleSetConfig} and all stateful inputs (the
 * windowed counters) are read through the {@link VelocityCounterStore} port.
 * New rule types are added by implementing this interface and inserting the
 * instance into the engine's ordered rule list.
 */
public interface Rule {

    /** Stable rule id (snake_case; reported as {@code rule_id} and in {@code rules_matched}). */
    String id();

    /**
     * Evaluates the request. {@code Outcome.DENY} short-circuits the engine;
     * {@code Outcome.REVIEW} accumulates toward a REVIEW decision.
     */
    RuleResult evaluate(EvaluationRequest request, RuleSetConfig config, VelocityCounterStore counters);
}
