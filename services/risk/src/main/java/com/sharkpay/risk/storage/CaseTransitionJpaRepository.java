package com.sharkpay.risk.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data repo for the case transition log (append-only, idempotent by id). */
public interface CaseTransitionJpaRepository extends JpaRepository<CaseTransitionEntity, UUID> {

    List<CaseTransitionEntity> findByCaseIdOrderByOccurredAtAsc(UUID caseId);
}
