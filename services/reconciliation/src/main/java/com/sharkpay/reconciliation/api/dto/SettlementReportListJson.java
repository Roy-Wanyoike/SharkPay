package com.sharkpay.reconciliation.api.dto;

import com.sharkpay.reconciliation.domain.SettlementReport;

import java.util.List;

/** A list of settlement reports. */
public record SettlementReportListJson(List<SettlementReportJson> reports, int count) {

    public static SettlementReportListJson of(List<SettlementReport> reports) {
        return new SettlementReportListJson(reports.stream().map(SettlementReportJson::of).toList(),
                reports.size());
    }
}
