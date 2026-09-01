package com.sharkpay.payments.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The V1 rail × currency fee table and the deterministic default-rail rule
 * (first rail in canonical order serving the currency).
 */
class FeeSchedulesTest {

    @Test
    void servesEveryDocumentedRailCurrencyPair() {
        assertThat(FeeSchedules.forRailAndCurrency(Rail.HONEYCOIN, "KES")).isPresent();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.HONEYCOIN, "USDC")).isPresent();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.MPESA, "KES")).isPresent();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.BANK, "KES")).isPresent();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.BANK, "USD")).isPresent();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.BANK, "EUR")).isPresent();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.BANK, "GBP")).isPresent();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.ON_CHAIN, "USDC")).isPresent();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.ON_CHAIN, "USDT")).isPresent();

        assertThat(FeeSchedules.forRailAndCurrency(Rail.HONEYCOIN, "USD")).isEmpty();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.MPESA, "USD")).isEmpty();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.ON_CHAIN, "KES")).isEmpty();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.BANK, "USDC")).isEmpty();
        assertThat(FeeSchedules.forRailAndCurrency(null, "KES")).isEmpty();
        assertThat(FeeSchedules.forRailAndCurrency(Rail.BANK, null)).isEmpty();
    }

    @Test
    void defaultRailFollowsCanonicalOrderDeterministically() {
        // honeycoin is first in canonical order and serves KES
        assertThat(FeeSchedules.defaultRailFor("KES")).contains(Rail.HONEYCOIN);
        // honeycoin serves USDC too
        assertThat(FeeSchedules.defaultRailFor("USDC")).contains(Rail.HONEYCOIN);
        // bank is the first rail serving USD/EUR/GBP after honeycoin misses
        assertThat(FeeSchedules.defaultRailFor("USD")).contains(Rail.BANK);
        assertThat(FeeSchedules.defaultRailFor("EUR")).contains(Rail.BANK);
        assertThat(FeeSchedules.defaultRailFor("GBP")).contains(Rail.BANK);
        // only on_chain serves USDT
        assertThat(FeeSchedules.defaultRailFor("USDT")).contains(Rail.ON_CHAIN);
    }

    @Test
    void unknownCurrenciesAreNotCollectableFailClosed() {
        assertThat(FeeSchedules.defaultRailFor("XYZ")).isEmpty();
        assertThat(FeeSchedules.defaultRailFor(null)).isEmpty();
        assertThat(FeeSchedules.isCollectable("KES")).isTrue();
        assertThat(FeeSchedules.isCollectable("XYZ")).isFalse();
    }

    @Test
    void canonicalOrderIsFixedForReplayability() {
        assertThat(Rail.canonicalOrder())
                .containsExactly(Rail.HONEYCOIN, Rail.MPESA, Rail.BANK, Rail.ON_CHAIN);
    }
}
