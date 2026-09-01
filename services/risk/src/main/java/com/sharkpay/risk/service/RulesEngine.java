package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Decision;
import com.sharkpay.risk.domain.DecisionResult;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.Rule;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.ports.VelocityCounterStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered rule engine. The first rule that returns {@code DENY} short-
 * circuits evaluation (no further rules run); {@code REVIEW} outcomes
 * accumulate and downgrade the final decision to REVIEW; otherwise the
 * decision is ALLOW. Result rule list is ordered and always non-empty.
 */
public final class RulesEngine {

    private final List<Rule> rules;

    public RulesEngine(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("at least one rule is required");
        }
        this.rules = List.copyOf(rules);
    }

    public RulesEngine(Rule... rules) {
        this(List.of(rules));
    }

    /** Engine order (default wiring): velocity, limits, geo, counterparty. */
    public static RulesEngine defaultEngine() {
        return new RulesEngine(new com.sharkpay.risk.domain.rules.VelocityRule(),
                new com.sharkpay.risk.domain.rules.LimitRule(),
                new com.sharkpay.risk.domain.rules.GeoRule(),
                new com.sharkpay.risk.domain.rules.CounterpartyRule());
    }

    public List<Rule> rules() {
        return rules;
    }

    public DecisionResult evaluate(EvaluationRequest request, RuleSetConfig config, VelocityCounterStore counters) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(counters, "counters must not be null");
        List<com.sharkpay.risk.domain.RuleResult> results = new ArrayList<>();
        Decision decision = Decision.ALLOW;
        for (Rule rule : rules) {
            com.sharkpay.risk.domain.RuleResult result = rule.evaluate(request, config, counters);
            results.add(result);
            if (result.outcome() == com.sharkpay.risk.domain.Outcome.DENY) {
                return new DecisionResult(Decision.DENY, results);
            }
            if (result.outcome() == com.sharkpay.risk.domain.Outcome.REVIEW) {
                decision = Decision.REVIEW;
            }
        }
        return new DecisionResult(decision, results);
    }
}
