package com.sharkpay.risk.fakes;

import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.ports.EvaluationRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory evaluation repository (id = evaluation id / idempotency key). */
public final class InMemoryEvaluationRepository implements EvaluationRepository {

    private final Map<String, Evaluation> store = new LinkedHashMap<>();

    @Override
    public Optional<Evaluation> findById(String evaluationId) {
        return Optional.ofNullable(store.get(evaluationId));
    }

    @Override
    public void save(Evaluation evaluation) {
        store.put(evaluation.evaluationId(), evaluation);
    }

    public int size() {
        return store.size();
    }
}
