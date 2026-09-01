package com.sharkpay.risk.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A persisted risk evaluation. {@code evaluationId} is the idempotency key
 * supplied by the caller; {@code ruleResults} is the ordered reason list
 * ({@code rules_matched} on the decision event).
 */
public record Evaluation(
        String evaluationId,
        EvaluationRequest request,
        Decision decision,
        List<RuleResult> ruleResults,
        Instant decidedAt) {

    public Evaluation {
        Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        if (ruleResults == null || ruleResults.isEmpty()) {
            throw new IllegalArgumentException("ruleResults must contain at least one entry");
        }
        if (!evaluationId.equals(request.evaluationId())) {
            throw new IllegalArgumentException("evaluationId does not match the evaluated request");
        }
        ruleResults = List.copyOf(ruleResults);
    }

    public boolean allowed() {
        return decision == Decision.ALLOW;
    }
}
