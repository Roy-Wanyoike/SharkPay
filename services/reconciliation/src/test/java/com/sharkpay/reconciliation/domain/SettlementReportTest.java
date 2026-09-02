package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;
import com.sharkpay.money.MoneyOverflowException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Settlement-report aggregation: per-currency both-side totals with exact
 * long addition (overflow fails loudly, never a silent wrap, never a
 * float), break summaries by taxonomy entry, rehydration.
 */
class SettlementReportTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T10:00:30Z");
    private static final ReconWindow WINDOW = new ReconWindow(
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"));

    @Test
    void aggregatesBothSideTotalsPerCurrency() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        SettlementReport report = SettlementReport.from("str_01", run,
                List.of(providerLine("hc_1", "CONFIRMED", 150_000, 500),
                        providerLine("hc_2", "CONFIRMED", 50_000, 250),
                        providerLine("hc_usd", "CONFIRMED", 100, "USD", 0)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 150_000, 500),
                        internalLine("int_2", "hc_2", "CONFIRMED", 40_000, 250)),
                T1);

        assertThat(report.runId()).isEqualTo("run_01");
        assertThat(report.id()).isEqualTo("str_01");
        assertThat(report.provider()).isEqualTo("honeycoin");
        assertThat(report.generatedAt()).isEqualTo(T1);
        assertThat(report.currencyLines()).hasSize(2);

        SettlementReport.CurrencyLine kes = report.currencyLines().get(0);
        assertThat(kes.currency()).isEqualTo("KES");
        assertThat(kes.providerLines()).isEqualTo(2);
        assertThat(kes.providerVolume()).isEqualTo(200_000L);
        assertThat(kes.providerFees()).isEqualTo(750L);
        assertThat(kes.internalLines()).isEqualTo(2);
        assertThat(kes.internalVolume()).isEqualTo(190_000L);
        assertThat(kes.internalFees()).isEqualTo(750L);
        assertThat(kes.matchedPairs()).isEqualTo(2);

        SettlementReport.CurrencyLine usd = report.currencyLines().get(1);
        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.providerLines()).isEqualTo(1);
        assertThat(usd.internalLines()).isZero();
        assertThat(usd.providerVolume()).isEqualTo(100L);
    }

    @Test
    void anEmptyRunProducesAnEmptyReport() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        SettlementReport report = SettlementReport.from("str_01", run, List.of(), List.of(), T1);
        assertThat(report.currencyLines()).isEmpty();
        assertThat(report.breakSummary().total()).isZero();
    }

    @Test
    void totalsOverflowFailsLoudlyNeverWraps() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        List<ProviderStatementLine> huge = List.of(
                providerLine("hc_1", "CONFIRMED", Long.MAX_VALUE / 2 + 1_000_000, 0),
                providerLine("hc_2", "CONFIRMED", Long.MAX_VALUE / 2 + 1_000_000, 0));
        assertThatThrownBy(() -> SettlementReport.from("str_01", run, huge, List.of(), T1))
                .isInstanceOf(MoneyOverflowException.class)
                .hasMessageContaining("settlement report total overflow");
    }

    @Test
    void withBreaksAttachesTheSummaryAndKeepsTheRest() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        SettlementReport report = SettlementReport.from("str_01", run,
                List.of(providerLine("hc_1", "CONFIRMED", 150_000, 500)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 149_500, 500)), T1);
        assertThat(report.breakSummary().total()).isZero();

        SettlementReport.BreakSummary summary = SettlementReport.BreakSummary.fromBreaks(List.of(
                new DetectedBreak(BreakType.MISSING_ON_PROVIDER, null, "int_2", null,
                        Money.of(9_000, "KES"), null, Money.of(0, "KES"), null, "CONFIRMED"),
                new DetectedBreak(BreakType.MISSING_INTERNAL, "hc_9", null,
                        Money.of(9_000, "KES"), null, Money.of(0, "KES"), null, "CONFIRMED", null),
                new DetectedBreak(BreakType.AMOUNT_MISMATCH, "hc_1", "int_1",
                        Money.of(150_000, "KES"), Money.of(149_500, "KES"), Money.of(500, "KES"),
                        Money.of(500, "KES"), "CONFIRMED", "CONFIRMED"),
                new DetectedBreak(BreakType.STATUS_MISMATCH, "hc_2", "int_2",
                        Money.of(1, "KES"), Money.of(1, "KES"), Money.of(0, "KES"),
                        Money.of(0, "KES"), "SUCCEEDED", "PENDING"),
                new DetectedBreak(BreakType.FEE_MISMATCH, "hc_3", "int_3",
                        Money.of(1, "KES"), Money.of(1, "KES"), Money.of(7, "KES"),
                        Money.of(5, "KES"), "CONFIRMED", "CONFIRMED"),
                new DetectedBreak(BreakType.CURRENCY_MISMATCH, "hc_4", "int_4",
                        Money.of(1, "USD"), Money.of(1, "KES"), Money.of(0, "USD"),
                        Money.of(0, "KES"), "CONFIRMED", "CONFIRMED")));

        SettlementReport withBreaks = report.withBreaks(summary);
        assertThat(withBreaks.breakSummary().missingOnProvider()).isEqualTo(1);
        assertThat(withBreaks.breakSummary().missingInternal()).isEqualTo(1);
        assertThat(withBreaks.breakSummary().amountMismatch()).isEqualTo(1);
        assertThat(withBreaks.breakSummary().statusMismatch()).isEqualTo(1);
        assertThat(withBreaks.breakSummary().feeMismatch()).isEqualTo(1);
        assertThat(withBreaks.breakSummary().currencyMismatch()).isEqualTo(1);
        assertThat(withBreaks.breakSummary().total()).isEqualTo(6);
        // the original report is untouched (withBreaks is a copy)
        assertThat(report.breakSummary().total()).isZero();
        // the carried fields stay
        assertThat(withBreaks.currencyLines()).isEqualTo(report.currencyLines());
        assertThat(withBreaks.id()).isEqualTo("str_01");
    }

    @Test
    void breakSummaryFromBreaksCountsEveryTaxonomyEntry() {
        SettlementReport.BreakSummary summary = SettlementReport.BreakSummary.fromBreaks(
                List.of(detected(BreakType.FEE_MISMATCH), detected(BreakType.FEE_MISMATCH),
                        detected(BreakType.AMOUNT_MISMATCH)));
        assertThat(summary.feeMismatch()).isEqualTo(2);
        assertThat(summary.amountMismatch()).isEqualTo(1);
        assertThat(summary.total()).isEqualTo(3);

        assertThat(SettlementReport.BreakSummary.fromBreaks(List.of()).total()).isZero();
    }

    @Test
    void rehydrateRestoresEveryField() {
        SettlementReport report = SettlementReport.rehydrate("str_9", "run_9", "honeycoin",
                WINDOW, T1,
                List.of(new SettlementReport.CurrencyLine("KES", 3, 300_000L, 750L, 2, 190_000L,
                        750L, 2)),
                new SettlementReport.BreakSummary(1, 1, 1, 1, 1, 1));
        assertThat(report.id()).isEqualTo("str_9");
        assertThat(report.currencyLines()).hasSize(1);
        assertThat(report.currencyLines().get(0).providerVolume()).isEqualTo(300_000L);
        assertThat(report.breakSummary().total()).isEqualTo(6);
    }

    private static DetectedBreak detected(BreakType type) {
        return new DetectedBreak(type, "hc_x", "int_x", Money.of(1, "KES"), Money.of(1, "KES"),
                Money.of(0, "KES"), Money.of(0, "KES"), "CONFIRMED", "CONFIRMED");
    }

    private static ProviderStatementLine providerLine(String ref, String status, long amountMinor,
                                                      long feeMinor) {
        return new ProviderStatementLine(ref, status, Money.of(amountMinor, "KES"),
                Money.of(feeMinor, "KES"), T0);
    }

    private static ProviderStatementLine providerLine(String ref, String status, long amountMinor,
                                                      String currency, long feeMinor) {
        return new ProviderStatementLine(ref, status, Money.of(amountMinor, currency),
                Money.of(feeMinor, "KES"), T0);
    }

    private static InternalLedgerLine internalLine(String internalRef, String providerRef,
                                                   String status, long amountMinor, long feeMinor) {
        return new InternalLedgerLine(internalRef, providerRef, status,
                Money.of(amountMinor, "KES"), Money.of(feeMinor, "KES"), T0);
    }
}
