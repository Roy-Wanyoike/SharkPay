package com.sharkpay.risk.storage;

import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.ports.EvaluationRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA adapter for {@link EvaluationRepository}. Untested locally (no
 * database in the sandbox, ADR 003); the mapping it delegates to
 * (EvaluationMapper) is unit tested.
 */
@Repository
public class EvaluationRepositoryAdapter implements EvaluationRepository {

    private final EvaluationJpaRepository repo;

    public EvaluationRepositoryAdapter(EvaluationJpaRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<Evaluation> findById(String evaluationId) {
        return repo.findById(java.util.UUID.fromString(evaluationId))
                .map(EvaluationMapper::toDomain);
    }

    @Override
    public void save(Evaluation evaluation) {
        repo.save(EvaluationMapper.toEntity(evaluation));
    }
}
