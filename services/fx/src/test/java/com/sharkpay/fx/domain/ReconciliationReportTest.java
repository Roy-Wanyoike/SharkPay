package com.sharkpay.fx.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reconciliation report read model: one row per currency with the net
 * fx-position and the number of ledger lines examined.
 */
class ReconciliationReportTest {

    @Test
    void carriesCurrencyNetPositionAndEntriesExamined() {
        ReconciliationReport report = new ReconciliationReport("USD", 10_000, 3);
        assertThat(report.currency()).isEqualTo("USD");
        assertThat(report.netPositionMinor()).isEqualTo(10_000);
        assertThat(report.entriesExamined()).isEqualTo(3);
        assertThat(report).isEqualTo(new ReconciliationReport("USD", 10_000, 3));
    }

    @Test
    void allowsZeroAndNegativeNetPositions() {
        assertThat(new ReconciliationReport("KES", -1_270_650, 2).netPositionMinor())
                .isEqualTo(-1_270_650);
        assertThat(new ReconciliationReport("EUR", 0, 0).entriesExamined()).isZero();
    }

    @Test
    void rejectsBlankCurrencyAndNegativeEntryCounts() {
        assertThatThrownBy(() -> new ReconciliationReport(null, 0, 0))
                .isInstanceOf(FxDomainException.class);
        assertThatThrownBy(() -> new ReconciliationReport("  ", 0, 0))
                .isInstanceOf(FxDomainException.class);
        assertThatThrownBy(() -> new ReconciliationReport("USD", 0, -1))
                .isInstanceOf(FxDomainException.class);
    }
}
