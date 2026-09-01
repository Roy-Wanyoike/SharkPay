package com.sharkpay.wallet.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalancesTest {

    @Test
    void zeroBalancesOfAnySupportedCurrency() {
        Balances zero = Balances.zero("usd");
        assertThat(zero.total()).isEqualTo(Money.zero("USD"));
        assertThat(zero.held()).isEqualTo(Money.zero("USD"));
        assertThat(zero.pending()).isEqualTo(Money.zero("USD"));
        assertThat(zero.available()).isEqualTo(Money.zero("USD"));
    }

    @Test
    void availableIsTotalMinusHeld() {
        Balances balances = new Balances(Money.of(12500, "KES"), Money.of(5000, "KES"),
                Money.zero("KES"));
        assertThat(balances.available()).isEqualTo(Money.of(7500, "KES"));
    }

    @Test
    void fullyHeldWalletHasZeroAvailable() {
        Balances balances = new Balances(Money.of(5000, "KES"), Money.of(5000, "KES"),
                Money.zero("KES"));
        assertThat(balances.available()).isEqualTo(Money.zero("KES"));
    }

    @Test
    void mismatchedPartitionsAreRejected() {
        assertThatThrownBy(() -> new Balances(Money.of(100, "KES"), Money.of(100, "USD"),
                Money.zero("KES")))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> new Balances(Money.of(100, "KES"), Money.zero("KES"),
                Money.zero("USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void negativePartitionsAreRejected() {
        assertThatThrownBy(() -> new Balances(Money.of(-1, "KES"), Money.zero("KES"),
                Money.zero("KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");
        assertThatThrownBy(() -> new Balances(Money.zero("KES"), Money.of(-1, "KES"),
                Money.zero("KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("held");
        assertThatThrownBy(() -> new Balances(Money.zero("KES"), Money.zero("KES"),
                Money.of(-1, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending");
    }

    @Test
    void availableBelowZeroIsReportedTruthfullyNotClamped() {
        // out-of-band ledger activity can push available negative; the wallet
        // service never fabricates a clamp — the ledger is the authority
        Balances balances = new Balances(Money.of(100, "KES"), Money.of(300, "KES"),
                Money.zero("KES"));
        assertThat(balances.available()).isEqualTo(Money.of(-200, "KES"));
    }
}
