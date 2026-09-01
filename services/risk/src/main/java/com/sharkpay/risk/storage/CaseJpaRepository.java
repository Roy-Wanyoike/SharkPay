package com.sharkpay.risk.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repo for cases rows. */
public interface CaseJpaRepository extends JpaRepository<CaseEntity, java.util.UUID> {
}
