package com.sharkpay.risk.ports;

import com.sharkpay.risk.domain.Evaluation;

import java.util.Optional;

/**
 * Persistence port for risk evaluations, keyed by the caller-supplied
 * evaluation id (idempotency key).
 */
public interface EvaluationRepository {

    Optional<Evaluation> findById(String evaluationId);

    void save(Evaluation evaluation);
}
