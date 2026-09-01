package com.sharkpay.risk.domain.exceptions;

/**
 * An evaluation id (idempotency key) was reused with a different request
 * body. The original evaluation result remains authoritative.
 */
public class EvaluationConflictException extends RiskException {

    public EvaluationConflictException(String evaluationId) {
        super("Evaluation " + evaluationId + " was already recorded with a different request payload");
    }
}
