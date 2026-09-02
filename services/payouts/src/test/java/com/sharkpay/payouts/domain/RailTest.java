package com.sharkpay.payouts.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rail enum: wire parsing (case-insensitive, dash-tolerant), the
 * rail/currency compatibility matrix (KES-only M-Pesa, fiat bank rails,
 * stablecoin on-chain rails) and the destination-detail field map used for
 * validation + redaction.
 */
class RailTest {

    @Test
    void fromWireParsesEveryRailCaseInsensitively() {
        assertThat(Rail.fromWire("mpesa")).isEqualTo(Rail.MPESA);
        assertThat(Rail.fromWire(" MPESA ")).isEqualTo(Rail.MPESA);
        assertThat(Rail.fromWire("bank")).isEqualTo(Rail.BANK);
        assertThat(Rail.fromWire("Bank")).isEqualTo(Rail.BANK);
        assertThat(Rail.fromWire("on_chain")).isEqualTo(Rail.ON_CHAIN);
        assertThat(Rail.fromWire("onchain")).isEqualTo(Rail.ON_CHAIN); // documented alias
        assertThat(Rail.fromWire("ON-CHAIN")).isEqualTo(Rail.ON_CHAIN);
    }

    @Test
    void fromWireRejectsNullAndUnknownRails() {
        assertThatThrownBy(() -> Rail.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rail is required");
        assertThatThrownBy(() -> Rail.fromWire("honeycoin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown rail");
        assertThatThrownBy(() -> Rail.fromWire("pesa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown rail"); // payouts.yaml PayoutRail typo is NOT a rail
    }

    @Test
    void wireNamesAndDestinationTypesMatchTheContract() {
        for (Rail rail : Rail.values()) {
            assertThat(rail.wireName()).isEqualTo(rail.name().toLowerCase());
            assertThat(rail.destinationType()).isEqualTo(rail.wireName());
            assertThat(rail.toString()).isEqualTo(rail.wireName());
        }
    }

    @Test
    void mpesaServesKesOnly() {
        assertThat(Rail.MPESA.supportsCurrency("KES")).isTrue();
        for (String currency : new String[]{"USD", "EUR", "GBP", "USDC", "USDT"}) {
            assertThat(Rail.MPESA.supportsCurrency(currency))
                    .as("mpesa + %s", currency)
                    .isFalse();
        }
    }

    @Test
    void bankServesTheFiatSetAndRefusesStablecoins() {
        for (String currency : new String[]{"KES", "USD", "EUR", "GBP"}) {
            assertThat(Rail.BANK.supportsCurrency(currency)).isTrue();
        }
        assertThat(Rail.BANK.supportsCurrency("USDC")).isFalse();
        assertThat(Rail.BANK.supportsCurrency("USDT")).isFalse();
    }

    @Test
    void onChainServesStablecoinsOnly() {
        assertThat(Rail.ON_CHAIN.supportsCurrency("USDC")).isTrue();
        assertThat(Rail.ON_CHAIN.supportsCurrency("USDT")).isTrue();
        for (String currency : new String[]{"KES", "USD", "EUR", "GBP"}) {
            assertThat(Rail.ON_CHAIN.supportsCurrency(currency)).isFalse();
        }
    }

    @Test
    void expectedDetailFieldsMatchTheDestinationContracts() {
        assertThat(Rail.MPESA.expectedDetailFields())
                .isEqualTo(Map.of("msisdn", "required"));
        assertThat(Rail.BANK.expectedDetailFields())
                .isEqualTo(Map.of("bank_code", "required", "account_number", "required"));
        assertThat(Rail.ON_CHAIN.expectedDetailFields())
                .isEqualTo(Map.of("network", "required", "address", "required"));
    }

    @Test
    void fromNameMapsEntityColumnsTolerantly() {
        assertThat(Rail.fromName("MPESA")).isEqualTo(Rail.MPESA);
        assertThat(Rail.fromName(" mpesa ")).isEqualTo(Rail.MPESA);
        assertThat(Rail.fromName("ON_CHAIN")).isEqualTo(Rail.ON_CHAIN);
        assertThatThrownBy(() -> Rail.fromName(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rail name is required");
        assertThatThrownBy(() -> Rail.fromName("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
