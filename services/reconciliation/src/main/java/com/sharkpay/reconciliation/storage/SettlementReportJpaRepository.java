package com.sharkpay.reconciliation.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository behind {@link JpaSettlementReportRepository}.
 */
@Repository
public interface SettlementReportJpaRepository extends JpaRepository<SettlementReportEntity, String> {

    Optional<SettlementReportEntity> findFirstByRunId(String runId);

    Optional<SettlementReportEntity>
    findFirstByProviderAndWindowFromAndWindowToOrderByGeneratedAtDesc(
            String provider, Instant windowFrom, Instant windowTo);

    List<SettlementReportEntity> findByProviderOrderByGeneratedAtDescIdDesc(String provider);
}
