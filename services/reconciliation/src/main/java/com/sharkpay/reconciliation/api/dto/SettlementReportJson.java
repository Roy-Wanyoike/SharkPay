package com.sharkpay.reconciliation.api.dto;

import com.sharkpay.reconciliation.domain.SettlementReport;

import java.time.Instant;
import java.util.List;

/**
 * The settlement report of one run (per provider, per window): both-side
 * totals and fees per currency, plus the break summary.
 */
public record SettlementReportJson(String id, String run_id, String provider, Instant window_from,
                                    Instant window_to, Instant generated_at,
                                    List<CurrencyLineJson> currencies, BreakSummaryJson breaks) {

    public static SettlementReportJson of(SettlementReport report) {
        return new SettlementReportJson(report.id(), report.runId(), report.provider(),
                report.window().from(), report.window().to(), report.generatedAt(),
                report.currencyLines().stream().map(CurrencyLineJson::of).toList(),
                BreakSummaryJson.of(report.breakSummary()));
    }

    /** One currency's both-side totals (minor units). */
    public record CurrencyLineJson(String currency, int provider_lines, long provider_volume_minor,
                                   long provider_fees_minor, int internal_lines,
                                   long internal_volume_minor, long internal_fees_minor,
                                   int matched_lines) {

        static CurrencyLineJson of(SettlementReport.CurrencyLine line) {
            return new CurrencyLineJson(line.currency(), line.providerLines(),
                    line.providerVolume(), line.providerFees(), line.internalLines(),
                    line.internalVolume(), line.internalFees(), line.matchedPairs());
        }
    }

    /** Break counts by taxonomy entry plus the total. */
    public record BreakSummaryJson(int missing_on_provider, int missing_internal, int amount_mismatch,
                                   int status_mismatch, int fee_mismatch, int currency_mismatch,
                                   int total) {

        static BreakSummaryJson of(SettlementReport.BreakSummary summary) {
            return new BreakSummaryJson(summary.missingOnProvider(), summary.missingInternal(),
                    summary.amountMismatch(), summary.statusMismatch(), summary.feeMismatch(),
                    summary.currencyMismatch(), summary.total());
        }
    }
}
