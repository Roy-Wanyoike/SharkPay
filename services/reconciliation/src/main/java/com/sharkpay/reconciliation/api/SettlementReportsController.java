package com.sharkpay.reconciliation.api;

import com.sharkpay.reconciliation.api.dto.SettlementReportJson;
import com.sharkpay.reconciliation.api.dto.SettlementReportListJson;
import com.sharkpay.reconciliation.domain.SettlementReport;
import com.sharkpay.reconciliation.service.GetSettlementReportUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Settlement-report surface (the finance daily report):
 *
 * <pre>
 *   GET /internal/recon/settlement-report?provider=&from=&to=   report of the exact window [from,to)
 *   GET /internal/recon/settlement-reports?provider=            reports of a provider, newest first
 * </pre>
 */
@RestController
public final class SettlementReportsController {

    private final GetSettlementReportUseCase settlementReports;

    public SettlementReportsController(GetSettlementReportUseCase settlementReports) {
        this.settlementReports = settlementReports;
    }

    /** The report of the run that covered exactly {@code [from, to)}. */
    @GetMapping("/internal/recon/settlement-report")
    public SettlementReportJson report(@RequestParam("provider") String provider,
                                       @RequestParam("from") Instant from,
                                       @RequestParam("to") Instant to) {
        return SettlementReportJson.of(settlementReports.byProviderAndWindow(provider, from, to));
    }

    /** The provider's reports, newest first. */
    @GetMapping("/internal/recon/settlement-reports")
    public SettlementReportListJson reports(@RequestParam("provider") String provider) {
        List<SettlementReport> reports = settlementReports.listByProvider(provider);
        return SettlementReportListJson.of(reports);
    }
}
