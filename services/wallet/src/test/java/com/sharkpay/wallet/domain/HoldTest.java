package com.sharkpay.wallet.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T10:05:00Z");
    private static final UUID SOURCE_REF = UUID.randomUUID();
    private static final String WALLET_ID = "wal_0123456789abcdef0123456789abcdef";

    @Test
    void placedHoldIsActiveWithZeroSplit() {
        Hold hold = Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(5000, "KES"), Source.PAYMENTS, SOURCE_REF, "risk cleared", T0);

        assertThat(hold.state()).isEqualTo(HoldState.ACTIVE);
        assertThat(hold.amount()).isEqualTo(Money.of(5000, "KES"));
        assertThat(hold.captured()).isEqualTo(Money.zero("KES"));
        assertThat(hold.released()).isEqualTo(Money.zero("KES"));
        assertThat(hold.source()).isEqualTo(Source.PAYMENTS);
        assertThat(hold.sourceRef()).isEqualTo(SOURCE_REF);
        assertThat(hold.reason()).isEqualTo("risk cleared");
        assertThat(hold.walletId()).isEqualTo(WALLET_ID);
    }

    @Test
    void releaseReturnsTheFullAmount() {
        Hold hold = placed(Money.of(5000, "KES"));

        hold.release(T1);

        assertThat(hold.state()).isEqualTo(HoldState.RELEASED);
        assertThat(hold.released()).isEqualTo(Money.of(5000, "KES"));
        assertThat(hold.captured()).isEqualTo(Money.zero("KES"));
        assertThat(hold.updatedAt()).isEqualTo(T1);
    }

    @Test
    void fullCaptureSettlesEverything() {
        Hold hold = placed(Money.of(5000, "KES"));

        hold.capture(Money.of(5000, "KES"), T1);

        assertThat(hold.state()).isEqualTo(HoldState.CAPTURED);
        assertThat(hold.captured()).isEqualTo(Money.of(5000, "KES"));
        assertThat(hold.released()).isEqualTo(Money.zero("KES"));
    }

    @Test
    void partialCaptureSettlesTheCapturedPartAndReleasesTheRemainder() {
        Hold hold = placed(Money.of(5000, "KES"));

        hold.capture(Money.of(3000, "KES"), T1);

        assertThat(hold.state()).isEqualTo(HoldState.CAPTURED);
        assertThat(hold.captured()).isEqualTo(Money.of(3000, "KES"));
        assertThat(hold.released()).isEqualTo(Money.of(2000, "KES"));
        // the money-safety invariant: captured + released == amount, exactly
        assertThat(hold.captured().add(hold.released())).isEqualTo(hold.amount());
    }

    @Test
    void captureAboveTheReservedAmountIsRejected() {
        Hold hold = placed(Money.of(5000, "KES"));
        assertThatThrownBy(() -> hold.capture(Money.of(5001, "KES"), T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the reserved amount");
        assertThat(hold.state()).isEqualTo(HoldState.ACTIVE);
    }

    @Test
    void zeroAndNegativeCaptureAmountsAreRejected() {
        Hold hold = placed(Money.of(5000, "KES"));
        assertThatThrownBy(() -> hold.capture(Money.zero("KES"), T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> hold.capture(Money.of(-1, "KES"), T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void captureWithAMismatchedCurrencyIsRejected() {
        Hold hold = placed(Money.of(5000, "KES"));
        assertThatThrownBy(() -> hold.capture(Money.of(3000, "USD"), T1))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThat(hold.state()).isEqualTo(HoldState.ACTIVE);
    }

    @Test
    void releasedAndCapturedAreTerminal() {
        Hold released = placed(Money.of(5000, "KES"));
        released.release(T1);
        assertThatThrownBy(() -> released.release(T1))
                .isInstanceOf(HoldStateException.class);
        assertThatThrownBy(() -> released.capture(Money.of(1, "KES"), T1))
                .isInstanceOf(HoldStateException.class);

        Hold captured = placed(Money.of(5000, "KES"));
        captured.capture(Money.of(1, "KES"), T1);
        assertThatThrownBy(() -> captured.capture(Money.of(1, "KES"), T1))
                .isInstanceOf(HoldStateException.class)
                .hasMessageContaining("capture a captured hold");
        assertThatThrownBy(() -> captured.release(T1))
                .isInstanceOf(HoldStateException.class)
                .hasMessageContaining("release a captured hold");
    }

    @Test
    void holdAmountMustBePositive() {
        assertThatThrownBy(() -> placed(Money.zero("KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> placed(Money.of(-100, "KES")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsAndWalletIdsAreValidated() {
        assertThatThrownBy(() -> Hold.place("hld_short", WALLET_ID, Money.of(1, "KES"),
                Source.PAYMENTS, SOURCE_REF, null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hld_");
        assertThatThrownBy(() -> Hold.place("hld_0123456789abcdef0123456789abcdef", "wal_short",
                Money.of(1, "KES"), Source.PAYMENTS, SOURCE_REF, null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wallet id");
    }

    @Test
    void terminalStateMustSplitTheAmountExactly() {
        // a corrupted re-hydration must not be silently accepted
        assertThatThrownBy(() -> new Hold("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(5000, "KES"), Source.PAYMENTS, SOURCE_REF, null, HoldState.CAPTURED,
                Money.of(4000, "KES"), Money.of(500, "KES"), T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split");
        assertThatThrownBy(() -> new Hold("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(5000, "KES"), Source.PAYMENTS, SOURCE_REF, null, HoldState.RELEASED,
                null, null, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split");
    }

    @Test
    void activeHoldMustNotCarrySplitAmounts() {
        assertThatThrownBy(() -> new Hold("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(5000, "KES"), Source.PAYMENTS, SOURCE_REF, null, HoldState.ACTIVE,
                Money.of(1, "KES"), Money.zero("KES"), T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active hold");
    }

    @Test
    void splitAmountsMustShareTheHoldCurrency() {
        assertThatThrownBy(() -> new Hold("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(5000, "KES"), Source.PAYMENTS, SOURCE_REF, null, HoldState.CAPTURED,
                Money.of(4000, "USD"), Money.of(1000, "KES"), T0, T0))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void reasonIsTrimmedAndOptional() {
        Hold withReason = Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(1, "KES"), Source.FX, SOURCE_REF, "  note  ", T0);
        Hold withoutReason = Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(1, "KES"), Source.FX, SOURCE_REF, "   ", T0);

        assertThat(withReason.reason()).isEqualTo("note");
        assertThat(withoutReason.reason()).isNull();
    }

    @Test
    void equalityIsById() {
        Hold a = placed(Money.of(100, "KES"));
        Hold b = Hold.place("hld_0123456789abcdef0123456789abcdee", WALLET_ID,
                Money.of(999, "USD"), Source.FX, SOURCE_REF, null, T0);
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo(Hold.place(a.id(), WALLET_ID, Money.of(1, "KES"),
                Source.OPS, UUID.randomUUID(), null, T1));
    }

    private static Hold placed(Money amount) {
        return Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID, amount,
                Source.PAYMENTS, SOURCE_REF, null, T0);
    }
}
