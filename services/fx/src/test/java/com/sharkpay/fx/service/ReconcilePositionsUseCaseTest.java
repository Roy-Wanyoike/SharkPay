package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.Direction;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.ports.LedgerLine;
import com.sharkpay.fx.ports.LedgerStatement;
import com.sharkpay.fx.testsupport.FxTestEnv;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Position reconciliation read model (WP-7 mission item): after conversions,
 * every involved currency's {@code fx-position:<CCY>} ledger account must
 * net to the expected signed position — source currencies positive (the
 * platform holds the sold currency), target currencies negative — with the
 * 4-leg convention observable per statement line.
 */
class ReconcilePositionsUseCaseTest {

    private final FxTestEnv env = new FxTestEnv();

    @Test
    void reconcilesEmptyToNoReports() {
        assertThat(env.reconcile.reconcile()).isEmpty();
    }

    @Test
    void netPositionsFollowTheFourLegConvention() {
        Quote first = env.newLockedQuote("USD", "KES", 10_000);   // target 1_270_650
        env.convert.convert("recon-1", first.id(), "wallet/src-USD", "wallet/dst-KES");

        // source currency position grows POSITIVE (credited legs 2)
        // target currency position grows NEGATIVE (debited legs 3)
        assertThat(env.reconcile.reconcile())
                .hasSize(2)
                .allSatisfy(report -> {
                    if ("USD".equals(report.currency())) {
                        assertThat(report.netPositionMinor()).isEqualTo(10_000);
                        assertThat(report.entriesExamined()).isEqualTo(1);
                    } else {
                        assertThat(report.currency()).isEqualTo("KES");
                        assertThat(report.netPositionMinor()).isEqualTo(-1_270_650);
                        assertThat(report.entriesExamined()).isEqualTo(1);
                    }
                });
    }

    @Test
    void statementsExposeThePostingLinesPerAccount() {
        Quote quote = env.newLockedQuote("USD", "KES", 10_000);
        env.convert.convert("recon-2", quote.id(), "wallet/src-USD", "wallet/dst-KES");

        LedgerStatement usd = env.ledger.getStatement("fx-position:USD");
        assertThat(usd.lines()).hasSize(1);
        LedgerLine line = usd.lines().get(0);
        assertThat(line.direction()).isEqualTo(Direction.CREDIT);
        assertThat(line.amountMinor()).isEqualTo(10_000);
        assertThat(line.transactionKey()).startsWith("fx:cnv_");

        LedgerStatement kes = env.ledger.getStatement("fx-position:KES");
        assertThat(kes.lines()).hasSize(1);
        assertThat(kes.lines().get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(kes.lines().get(0).amountMinor()).isEqualTo(1_270_650);

        assertThat(env.ledger.getStatement("fx-position:EUR").lines()).isEmpty();
        assertThat(env.ledger.getStatement("fx-position:EUR").netPositionMinor()).isZero();
    }

    @Test
    void repeatedConversionsAccumulateIntoTheSamePositions() {
        Quote firstQuote = env.newLockedQuote("USD", "KES", 10_000);
        Quote secondQuote = env.newLockedQuote("EUR", "KES", 2_000);
        ConvertUseCase.Result first = env.convert.convert("recon-3", firstQuote.id(), "src", "dst");
        ConvertUseCase.Result second = env.convert.convert("recon-4", secondQuote.id(), "src", "dst");

        List<com.sharkpay.fx.domain.ReconciliationReport> reports = env.reconcile.reconcile();

        assertThat(reports).extracting(report -> report.currency())
                .containsExactly("EUR", "KES", "USD");
        assertThat(reports).allSatisfy(report -> {
            switch (report.currency()) {
                case "USD" -> assertThat(report.netPositionMinor()).isEqualTo(10_000);
                case "EUR" -> assertThat(report.netPositionMinor()).isEqualTo(2_000);
                case "KES" -> assertThat(report.netPositionMinor())
                        .isEqualTo(-(first.conversion().targetAmount().amountMinor()
                                + second.conversion().targetAmount().amountMinor()));
                default -> throw new AssertionError("unexpected currency " + report.currency());
            }
        });
    }
}
