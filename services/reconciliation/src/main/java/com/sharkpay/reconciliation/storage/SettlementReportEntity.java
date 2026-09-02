package com.sharkpay.reconciliation.storage;

import com.sharkpay.reconciliation.domain.SettlementReport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code settlement_reports} table: one report per
 * completed run. Currency lines are a JSON column (storage-internal exact
 * round-trip via {@link StorageJson}).
 */
@Entity
@Table(name = "settlement_reports")
public class SettlementReportEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "run_id", nullable = false, length = 40)
    private String runId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "window_from", nullable = false)
    private Instant windowFrom;

    @Column(name = "window_to", nullable = false)
    private Instant windowTo;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "currency_lines_json", nullable = false, columnDefinition = "text")
    private String currencyLinesJson;

    @Column(name = "missing_on_provider", nullable = false)
    private int missingOnProvider;

    @Column(name = "missing_internal", nullable = false)
    private int missingInternal;

    @Column(name = "amount_mismatch", nullable = false)
    private int amountMismatch;

    @Column(name = "status_mismatch", nullable = false)
    private int statusMismatch;

    @Column(name = "fee_mismatch", nullable = false)
    private int feeMismatch;

    @Column(name = "currency_mismatch", nullable = false)
    private int currencyMismatch;

    protected SettlementReportEntity() {
    }

    public SettlementReportEntity(String id, String runId, String provider, Instant windowFrom,
                                  Instant windowTo, Instant generatedAt, String currencyLinesJson,
                                  int missingOnProvider, int missingInternal, int amountMismatch,
                                  int statusMismatch, int feeMismatch, int currencyMismatch) {
        this.id = id;
        this.runId = runId;
        this.provider = provider;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.generatedAt = generatedAt;
        this.currencyLinesJson = currencyLinesJson;
        this.missingOnProvider = missingOnProvider;
        this.missingInternal = missingInternal;
        this.amountMismatch = amountMismatch;
        this.statusMismatch = statusMismatch;
        this.feeMismatch = feeMismatch;
        this.currencyMismatch = currencyMismatch;
    }

    /** Maps the domain report onto a fresh entity. */
    public static SettlementReportEntity fromDomain(SettlementReport report) {
        SettlementReport.BreakSummary summary = report.breakSummary();
        return new SettlementReportEntity(report.id(), report.runId(), report.provider(),
                report.window().from(), report.window().to(), report.generatedAt(),
                StorageJson.writeCurrencyLines(report.currencyLines()),
                summary.missingOnProvider(), summary.missingInternal(), summary.amountMismatch(),
                summary.statusMismatch(), summary.feeMismatch(), summary.currencyMismatch());
    }

    /** Restores the domain value object. */
    public SettlementReport toDomain() {
        return SettlementReport.rehydrate(id, runId, provider,
                new com.sharkpay.reconciliation.domain.ReconWindow(windowFrom, windowTo),
                generatedAt, StorageJson.readCurrencyLines(currencyLinesJson),
                new SettlementReport.BreakSummary(missingOnProvider, missingInternal, amountMismatch,
                        statusMismatch, feeMismatch, currencyMismatch));
    }

    public String getId() {
        return id;
    }
}
