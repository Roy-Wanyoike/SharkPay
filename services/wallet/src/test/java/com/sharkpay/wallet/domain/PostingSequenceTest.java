package com.sharkpay.wallet.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingSequenceTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID ENTRY = UUID.randomUUID();
    private static final UUID SOURCE_REF = UUID.randomUUID();

    @Test
    void emptySequenceHasZeroTotalAndEmptyStatement() {
        PostingSequence sequence = new PostingSequence("wal_x", "KES");
        assertThat(sequence.totalMinor()).isZero();
        assertThat(sequence.total()).isEqualTo(Money.zero("KES"));
        assertThat(sequence.statement()).isEmpty();
        assertThat(sequence.size()).isZero();
    }

    @Test
    void creditsAndDebitsFoldIntoTheRunningBalance() {
        PostingSequence sequence = new PostingSequence("wal_x", "KES");

        assertThat(sequence.apply(credit(101, 500))).isTrue();
        assertThat(sequence.apply(debit(102, 200))).isTrue();
        assertThat(sequence.apply(credit(103, 50))).isTrue();

        assertThat(sequence.totalMinor()).isEqualTo(350L);
        List<StatementLine> lines = sequence.statement();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).balanceAfter()).isEqualTo(Money.of(500, "KES"));
        assertThat(lines.get(1).balanceAfter()).isEqualTo(Money.of(300, "KES"));
        assertThat(lines.get(2).balanceAfter()).isEqualTo(Money.of(350, "KES"));
    }

    @Test
    void duplicatePostingIdsAreNoOps() {
        PostingSequence sequence = new PostingSequence("wal_x", "KES");
        assertThat(sequence.apply(credit(101, 500))).isTrue();
        assertThat(sequence.apply(credit(101, 999))).isFalse();  // duplicate delivery
        assertThat(sequence.apply(credit(101, 500))).isFalse();

        assertThat(sequence.totalMinor()).isEqualTo(500L);
        assertThat(sequence.size()).isEqualTo(1);
    }

    @Test
    void outOfOrderApplicationConvergesToTheInOrderProjection() {
        PostingSequence inOrder = new PostingSequence("wal_x", "KES");
        inOrder.apply(credit(101, 1000));
        inOrder.apply(debit(102, 300));
        inOrder.apply(credit(103, 200));

        PostingSequence outOfOrder = new PostingSequence("wal_x", "KES");
        outOfOrder.apply(credit(103, 200));   // future first
        outOfOrder.apply(credit(101, 1000));
        outOfOrder.apply(debit(102, 300));

        assertThat(outOfOrder.totalMinor()).isEqualTo(inOrder.totalMinor()).isEqualTo(900L);
        assertThat(outOfOrder.statement())
                .extracting(StatementLine::balanceAfter)
                .containsExactlyElementsOf(inOrder.statement().stream()
                        .map(StatementLine::balanceAfter).toList());
        // every line's balance_after is recomputed in posting order
        assertThat(outOfOrder.statement().stream().map(line -> line.leg().postingId()))
                .containsExactly(101L, 102L, 103L);
        assertThat(outOfOrder.statement().get(0).balanceAfter()).isEqualTo(Money.of(1000, "KES"));
        assertThat(outOfOrder.statement().get(1).balanceAfter()).isEqualTo(Money.of(700, "KES"));
        assertThat(outOfOrder.statement().get(2).balanceAfter()).isEqualTo(Money.of(900, "KES"));
    }

    @Test
    void aLegThatWouldDriveTheBalanceNegativeIsRejectedWhole() {
        PostingSequence sequence = new PostingSequence("wal_x", "KES");
        sequence.apply(credit(101, 100));

        assertThatThrownBy(() -> sequence.apply(debit(102, 101)))
                .isInstanceOf(ProjectionInconsistencyException.class)
                .hasMessageContaining("never go negative");

        // the sequence is unchanged — the offending event is dead-lettered
        assertThat(sequence.size()).isEqualTo(1);
        assertThat(sequence.totalMinor()).isEqualTo(100L);
    }

    @Test
    void overflowIsRejectedWithoutPartialState() {
        PostingSequence sequence = new PostingSequence("wal_x", "KES");
        sequence.apply(credit(101, Long.MAX_VALUE / 2 + 1));
        sequence.apply(credit(102, Long.MAX_VALUE / 2));

        assertThatThrownBy(() -> sequence.apply(credit(103, 2)))
                .isInstanceOf(ProjectionInconsistencyException.class)
                .hasMessageContaining("overflows int64");
        assertThat(sequence.totalMinor()).isEqualTo(Long.MAX_VALUE);
        assertThat(sequence.size()).isEqualTo(2);
    }

    @Test
    void legCurrencyMustMatchTheWalletCurrency() {
        PostingSequence sequence = new PostingSequence("wal_x", "KES");
        assertThatThrownBy(() -> sequence.apply(credit(101, 100, "USD")))
                .isInstanceOf(ProjectionInconsistencyException.class)
                .hasMessageContaining("does not match wallet");
        assertThat(sequence.size()).isZero();
    }

    @Test
    void auditTotalCrossChecksTheLongFoldWithBigInteger() {
        PostingSequence sequence = new PostingSequence("wal_x", "KES");
        sequence.apply(credit(101, Long.MAX_VALUE / 2));
        sequence.apply(credit(102, Long.MAX_VALUE / 2));
        // long fold would be at exactly Long.MAX_VALUE - 1 … BigInteger agrees
        assertThat(sequence.auditTotal()).isEqualTo(
                BigInteger.valueOf(Long.MAX_VALUE - 1));
    }

    @Test
    void negativeInputLegsAreRejectedByTheLegRecord() {
        assertThatThrownBy(() -> new ProjectionLeg(0, ENTRY, "capture", Direction.CREDIT,
                Money.of(1, "KES"), Source.PAYMENTS, SOURCE_REF, null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postingId");
        assertThatThrownBy(() -> new ProjectionLeg(1, ENTRY, "capture", Direction.CREDIT,
                Money.zero("KES"), Source.PAYMENTS, SOURCE_REF, null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new ProjectionLeg(1, null, "capture", Direction.CREDIT,
                Money.of(1, "KES"), Source.PAYMENTS, SOURCE_REF, null, T0))
                .isInstanceOf(NullPointerException.class);
    }

    private static ProjectionLeg credit(long postingId, long amountMinor) {
        return credit(postingId, amountMinor, "KES");
    }

    private static ProjectionLeg credit(long postingId, long amountMinor, String currency) {
        return new ProjectionLeg(postingId, ENTRY, "capture", Direction.CREDIT,
                Money.of(amountMinor, currency), Source.PAYMENTS, SOURCE_REF, null, T0);
    }

    private static ProjectionLeg debit(long postingId, long amountMinor) {
        return new ProjectionLeg(postingId, ENTRY, "hold", Direction.DEBIT,
                Money.of(amountMinor, "KES"), Source.PAYOUTS, SOURCE_REF, "payout settled", T0);
    }
}
