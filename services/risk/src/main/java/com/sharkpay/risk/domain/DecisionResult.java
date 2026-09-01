package com.sharkpay.risk.domain;

import java.util.List;
import java.util.Objects;

/**
 * Result of running the rule engine: the final {@link Decision} plus the
 * ordered rule results that produced it. Always at least one rule result —
 * the {@code risk.decision.v1} contract requires {@code rules_matched} with
 * {@code minItems: 1}.
 */
public record DecisionResult(Decision decision, List<RuleResult> ruleResults) {

    public DecisionResult {
        Objects.requireNonNull(decision, "decision must not be null");
        if (ruleResults == null || ruleResults.isEmpty()) {
            throw new IllegalArgumentException("ruleResults must contain at least one entry");
        }
        ruleResults = List.copyOf(ruleResults);
    }
}
