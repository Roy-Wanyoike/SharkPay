package com.sharkpay.reconciliation.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository behind {@link JpaCompensationEntryRepository}.
 */
@Repository
public interface CompensationEntryJpaRepository extends JpaRepository<CompensationEntryEntity, String> {

    List<CompensationEntryEntity> findByBreakIdOrderByCompensationKeyAsc(String breakId);

    long countByBreakId(String breakId);
}
