package com.sharkpay.risk.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repo for evaluations rows (id = evaluation id / idempotency key). */
public interface EvaluationJpaRepository extends JpaRepository<EvaluationEntity, java.util.UUID> {
}
