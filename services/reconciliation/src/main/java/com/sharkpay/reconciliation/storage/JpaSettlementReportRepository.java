package com.sharkpay.reconciliation.storage;

import com.sharkpay.reconciliation.domain.SettlementReport;
import com.sharkpay.reconciliation.ports.SettlementReportRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for the {@code SettlementReportRepository} port. The
 * (provider, exact window) lookup returns the newest report for that
 * window — a re-run of the same window produces a newer report.
 */
@Repository
public final class JpaSettlementReportRepository implements SettlementReportRepository {

    private final SettlementReportJpaRepository jpa;

    public JpaSettlementReportRepository(SettlementReportJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(SettlementReport report) {
        jpa.save(SettlementReportEntity.fromDomain(report));
    }

    @Override
    public Optional<SettlementReport> findByRunId(String runId) {
        return jpa.findFirstByRunId(runId).map(SettlementReportEntity::toDomain);
    }

    @Override
    public Optional<SettlementReport> findByProviderAndWindow(String provider, Instant from,
                                                               Instant to) {
        return jpa
                .findFirstByProviderAndWindowFromAndWindowToOrderByGeneratedAtDesc(provider, from, to)
                .map(SettlementReportEntity::toDomain);
    }

    @Override
    public List<SettlementReport> listByProvider(String provider) {
        return jpa.findByProviderOrderByGeneratedAtDescIdDesc(provider).stream()
                .map(SettlementReportEntity::toDomain).toList();
    }
}
