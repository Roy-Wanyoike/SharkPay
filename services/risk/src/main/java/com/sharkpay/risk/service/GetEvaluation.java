package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.domain.exceptions.EvaluationNotFoundException;
import com.sharkpay.risk.ports.EvaluationRepository;
import org.springframework.stereotype.Service;

/** Fetch a stored evaluation by id (idempotency key). */
@Service
public class GetEvaluation {

    private final EvaluationRepository evaluations;

    public GetEvaluation(EvaluationRepository evaluations) {
        this.evaluations = evaluations;
    }

    public Evaluation get(String evaluationId) {
        return evaluations.findById(evaluationId)
                .orElseThrow(() -> new EvaluationNotFoundException(evaluationId));
    }
}
