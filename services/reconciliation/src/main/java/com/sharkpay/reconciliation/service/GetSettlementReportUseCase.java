package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.ReconWindow;
import com.sharkpay.reconciliation.domain.SettlementReport;
import com.sharkpay.reconciliation.ports.SettlementReportRepository;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Read side: the settlement reports (the daily finance report — RB-7's
 * "daily recon report non-zero" symptom). Addressable by run, by exact
 * (provider, window), or listed per provider.
 */
public final class GetSettlementReportUseCase {

    private final SettlementReportRepository reports;

    public GetSettlementReportUseCase(SettlementReportRepository reports) {
        this.reports = Objects.requireNonNull(reports, "settlementReportRepository is required");
    }

    public SettlementReport byRun(String runId) {
        return reports.findByRunId(runId)
                .orElseThrow(() -> new NoSuchElementException(
                        "no settlement report for run " + runId));
    }

    /** The report of the run that covered exactly {@code [from, to)}. */
    public SettlementReport byProviderAndWindow(String provider, Instant from, Instant to) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        ReconWindow window = new ReconWindow(from, to);
        return reports.findByProviderAndWindow(provider.trim(), window.from(), window.to())
                .orElseThrow(() -> new NoSuchElementException(
                        "no settlement report for provider " + provider.trim() + " and window ["
                                + window.from() + ", " + window.to() + ")"));
    }

    public List<SettlementReport> listByProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        return reports.listByProvider(provider.trim());
    }
}
