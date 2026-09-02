package com.sharkpay.reconciliation.ports;

import com.sharkpay.reconciliation.domain.SettlementReport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence of {@link SettlementReport}s — one report per completed run,
 * addressable by run or by (provider, exact window).
 */
public interface SettlementReportRepository {

    void save(SettlementReport report);

    Optional<SettlementReport> findByRunId(String runId);

    /** The report of the run that covered exactly {@code [from, to)}. */
    Optional<SettlementReport> findByProviderAndWindow(String provider, Instant from, Instant to);

    /** Reports of one provider, newest first. */
    List<SettlementReport> listByProvider(String provider);
}
