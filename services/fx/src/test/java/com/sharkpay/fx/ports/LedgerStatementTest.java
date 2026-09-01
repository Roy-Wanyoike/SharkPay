package com.sharkpay.fx.ports;

import com.sharkpay.fx.domain.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The statement read model used by position reconciliation: lines are
 * immutable, the net position is Σ credits − Σ debits, and the statement
 * rejects malformed construction (blank account ref, null lines).
 */
class LedgerStatementTest {

    @Test
    void netPositionSumsCreditsMinusDebits() {
        LedgerStatement flat = new LedgerStatement("fx-position:USD", List.of(
                new LedgerLine("fx:cnv_a", Direction.CREDIT, 10_000),
                new LedgerLine("fx:cnv_a", Direction.DEBIT, 4_000)));
        assertThat(flat.netPositionMinor()).isEqualTo(6_000);

        LedgerStatement negative = new LedgerStatement("fx-position:KES", List.of(
                new LedgerLine("fx:cnv_a", Direction.DEBIT, 1_270_650)));
        assertThat(negative.netPositionMinor()).isEqualTo(-1_270_650);

        assertThat(new LedgerStatement("fx-position:EUR", List.of()).netPositionMinor()).isZero();
    }

    @Test
    void linesAreDefensivelyCopied() {
        List<LedgerLine> mutable = new ArrayList<>();
        mutable.add(new LedgerLine("fx:cnv_a", Direction.CREDIT, 100));

        LedgerStatement statement = new LedgerStatement("fx-position:USD", mutable);
        mutable.add(new LedgerLine("fx:cnv_b", Direction.DEBIT, 50));

        assertThat(statement.lines()).hasSize(1);
        assertThatThrownBy(() -> statement.lines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankAccountRefsAndNullLines() {
        assertThatThrownBy(() -> new LedgerStatement(" ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerStatement("fx-position:USD", null))
                .isInstanceOf(NullPointerException.class);
    }
}
