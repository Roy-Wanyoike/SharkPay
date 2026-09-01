package com.sharkpay.risk.domain.exceptions;

/** Evaluation id not found. */
public class EvaluationNotFoundException extends RiskException {

    public EvaluationNotFoundException(String evaluationId) {
        super("Evaluation " + evaluationId + " not found");
    }
}
