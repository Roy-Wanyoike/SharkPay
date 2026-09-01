package com.sharkpay.payments.domain;

import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wire enums and value objects of the payments domain. */
class WireValuesTest {

    @Test
    void railsParseFromWireNames() {
        for (Rail rail : Rail.values()) {
            assertThat(Rail.fromWire(rail.wireName())).isEqualTo(rail);
            assertThat(Rail.fromWire(" " + rail.wireName())).isEqualTo(rail);
        }
        assertThat(Rail.HONEYCOIN.wireName()).isEqualTo("honeycoin");
        assertThat(Rail.ON_CHAIN.wireName()).isEqualTo("on_chain");
        assertThatThrownBy(() -> Rail.fromWire("paypal"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown rail");
        assertThatThrownBy(() -> Rail.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rail is required");
    }

    @Test
    void destinationsValidateTheirShape() {
        Destination internal = Destination.internalWallet("wal_0123456789abcdef0123456789abcdef");
        assertThat(internal.type()).isEqualTo(Destination.Type.INTERNAL_WALLET);
        assertThat(internal.internalWalletId()).isEqualTo(Optional.of("wal_0123456789abcdef0123456789abcdef"));
        assertThat(internal.toString()).contains("internal");

        Destination external = Destination.externalRail("msisdn:+254712345678");
        assertThat(external.type()).isEqualTo(Destination.Type.EXTERNAL_RAIL);
        assertThat(external.internalWalletId()).isEmpty();
        assertThat(external.toString()).contains("external");

        Destination fx = Destination.fxQuote("fxq_0123456789abcdef0123456789abcdef");
        assertThat(fx.type()).isEqualTo(Destination.Type.FX_QUOTE);
        assertThat(fx.toString()).contains("fx");

        assertThatThrownBy(() -> Destination.internalWallet("wal_short"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wal_");
        assertThatThrownBy(() -> Destination.internalWallet(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Destination.externalRail(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("external rail destination");
        assertThatThrownBy(() -> Destination.fxQuote("fxq_bad"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fxq_");
        assertThatThrownBy(() -> new Destination(null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("type");
    }

    @Test
    void providerCandidatesValidateTheirEconomics() {
        assertThatThrownBy(() -> new ProviderCandidate(" ", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 0, 0, 0, false, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provider id");
        assertThatThrownBy(() -> new ProviderCandidate("x", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), -1, 0, 0, false, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cost bps");
        assertThatThrownBy(() -> new ProviderCandidate("x", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 0, -1, 0, false, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("p99");
        assertThatThrownBy(() -> new ProviderCandidate("x", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 0, 0, 10_001, false, 0, null,
                null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("success rate");
        assertThatThrownBy(() -> new ProviderCandidate("x", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 0, 0, 0, false, 3, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tier rank");
        assertThatThrownBy(() -> new ProviderCandidate("x", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 0, 0, 0, false, 0, 10L, 5L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min txn");
        assertThatThrownBy(() -> new ProviderCandidate("x", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 0, 0, 0, false, 0, -1L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min txn");
        assertThatThrownBy(() -> new ProviderCandidate("x", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 0, 0, 0, false, 0, null, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max txn");
    }

    @Test
    void stateTransitionsValidateTheirRowShape() {
        StateTransition row = new StateTransition("pay_0123456789abcdef0123456789abcdef", 1,
                null, PaymentState.CREATED, "  ", null, PaymentsTestEnv.START);
        assertThat(row.reason()).isNull(); // blank reason normalised to null
        assertThat(row.toString()).contains("∅").contains("CREATED");

        assertThatThrownBy(() -> new StateTransition(" ", 1, null, PaymentState.CREATED, null,
                null, PaymentsTestEnv.START))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paymentId");
        assertThatThrownBy(() -> new StateTransition("pay_0123456789abcdef0123456789abcdef", 0,
                null, PaymentState.CREATED, null, null, PaymentsTestEnv.START))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("seq");
        assertThatThrownBy(() -> new StateTransition("pay_0123456789abcdef0123456789abcdef", 1,
                null, null, null, null, PaymentsTestEnv.START))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("to-state");
        assertThatThrownBy(() -> new StateTransition("pay_0123456789abcdef0123456789abcdef", 1,
                null, PaymentState.CREATED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("occurredAt");
    }
}
