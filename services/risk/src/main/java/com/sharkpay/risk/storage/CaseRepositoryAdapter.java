package com.sharkpay.risk.storage;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.ports.CaseRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA adapter for {@link CaseRepository}: saves the case row and appends the
 * transitions that are not persisted yet (ids are domain-assigned/stable).
 * Untested locally (ADR 003); CaseMapper is unit tested.
 */
@Repository
public class CaseRepositoryAdapter implements CaseRepository {

    private final CaseJpaRepository cases;
    private final CaseTransitionJpaRepository transitions;

    public CaseRepositoryAdapter(CaseJpaRepository cases, CaseTransitionJpaRepository transitions) {
        this.cases = cases;
        this.transitions = transitions;
    }

    @Override
    public Case save(Case caseEntity) {
        cases.save(CaseMapper.toEntity(caseEntity));
        for (CaseTransitionEntity transition : CaseMapper.toTransitionEntities(caseEntity)) {
            if (!transitions.existsById(transition.id)) {
                transitions.save(transition);
            }
        }
        return caseEntity;
    }

    @Override
    public Optional<Case> findById(java.util.UUID id) {
        return cases.findById(id)
                .map(caseEntity -> CaseMapper.toDomain(caseEntity,
                        transitions.findByCaseIdOrderByOccurredAtAsc(id)));
    }
}
