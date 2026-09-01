package com.sharkpay.risk.domain;

import java.util.Objects;

/**
 * Outcome of one rule for one evaluation. Kept in the decision's ordered
 * reason list and serialized as {@code {rule_id, outcome, reason}}.
 */
public record RuleResult(String ruleId, Outcome outcome, String reason) {

    public RuleResult {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
