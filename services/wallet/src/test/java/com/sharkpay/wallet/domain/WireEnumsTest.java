package com.sharkpay.wallet.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wire-name round trips of the contract enums (JSON @JsonValue/@JsonCreator). */
class WireEnumsTest {

    @Test
    void holdStateParsesCaseInsensitivelyAndRejectsUnknownValues() {
        for (HoldState state : HoldState.values()) {
            assertThat(HoldState.fromWire(state.wireName())).isEqualTo(state);
            assertThat(HoldState.fromWire(" " + state.wireName().toUpperCase() + " "))
                    .isEqualTo(state);
        }
        assertThatThrownBy(() -> HoldState.fromWire("expired"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown hold state");
        assertThatThrownBy(() -> HoldState.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void walletStatusParsesAndRejectsUnknownValues() {
        assertThat(WalletStatus.fromWire("ACTIVE")).isEqualTo(WalletStatus.ACTIVE);
        assertThat(WalletStatus.fromWire(" frozen ")).isEqualTo(WalletStatus.FROZEN);
        assertThatThrownBy(() -> WalletStatus.fromWire("closed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown wallet status");
        assertThatThrownBy(() -> WalletStatus.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directionParsesAndRejectsUnknownValues() {
        assertThat(Direction.fromWire("debit")).isEqualTo(Direction.DEBIT);
        assertThat(Direction.fromWire("CREDIT")).isEqualTo(Direction.CREDIT);
        assertThatThrownBy(() -> Direction.fromWire("sideways"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown posting direction");
    }

    @Test
    void sourceParsesAndRejectsUnknownValues() {
        for (Source source : Source.values()) {
            assertThat(Source.fromWire(source.wireName())).isEqualTo(source);
        }
        assertThat(Source.fromWire(" PAYMENTS ")).isEqualTo(Source.PAYMENTS);
        assertThatThrownBy(() -> Source.fromWire("ledger"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown source domain");
        assertThatThrownBy(() -> Source.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullWireValuesAreRejectedUniformly() {
        assertThatThrownBy(() -> HoldState.fromWire(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Direction.fromWire(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Source.fromWire(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
