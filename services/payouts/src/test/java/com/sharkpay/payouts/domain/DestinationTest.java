package com.sharkpay.payouts.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Destination value object (contracts/openapi/v1/payouts.yaml
 * PayoutDestination oneOf): construction always validates, so an instance
 * can never hold a malformed msisdn, bank reference or EVM address; details
 * are fully persisted but redacted in events (only the rail type travels).
 */
class DestinationTest {

    @Test
    void mpesaDestinationsValidateTheMsisdnShape() {
        assertThat(new Destination("mpesa", "+254712345678", null, null, null, null, null, null)
                .type()).isEqualTo("mpesa");
        assertThat(new Destination("MPESA", " 254712345678 ", null, null, null, null, null, null)
                .rail()).isEqualTo(Rail.MPESA); // type normalized, msisdn trimmed by pattern
        // contract pattern ^\+?[1-9][0-9]{6,14}$: 7-digit local numbers are legal
        for (String bad : new String[]{"+0123456789", "25471", "+2547123456789012345",
                "abc-def-ghij"}) {
            assertThatThrownBy(() -> new Destination("mpesa", bad, null, null, null, null, null,
                    null))
                    .as("msisdn %s", bad)
                    .isInstanceOf(UnsupportedDestinationException.class)
                    .hasMessageContaining("msisdn");
        }
        assertThatThrownBy(() -> new Destination("mpesa", null, null, null, null, null, null, null))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("msisdn is required");
        assertThatThrownBy(() -> new Destination("mpesa", "  ", null, null, null, null, null, null))
                .isInstanceOf(UnsupportedDestinationException.class);
    }

    @Test
    void bankDestinationsRequireBankCodeAndAccountNumber() {
        Destination bank = new Destination("bank", null, "KCB-001", "ACC-7731", "Jane Doe", "KE",
                null, null);
        assertThat(bank.rail()).isEqualTo(Rail.BANK);
        assertThat(new Destination("BANK", null, "code", "acct", null, null, null, null).type())
                .isEqualTo("bank");
        assertThatThrownBy(() -> new Destination("bank", null, null, "ACC-1", null, null, null,
                null))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("bank_code is required");
        assertThatThrownBy(() -> new Destination("bank", null, "KCB", null, null, null, null,
                null))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("account_number is required");
        assertThatThrownBy(() -> new Destination("bank", null, "x".repeat(65), "ACC", null, null,
                null, null))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("bank_code too long");
        assertThatThrownBy(() -> new Destination("bank", null, "KCB", "a".repeat(65), null, null,
                null, null))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("account_number too long");
    }

    @Test
    void onChainDestinationsValidateNetworkAndEvmAddress() {
        String address = "0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d";
        Destination onChain = new Destination("on_chain", null, null, null, null, null, "base",
                address);
        assertThat(onChain.rail()).isEqualTo(Rail.ON_CHAIN);
        // dash + case normalized; the 0x prefix stays lowercase per the
        // contract pattern ^0x[0-9a-fA-F]{40}$ (hex digits are case-free)
        assertThat(new Destination("on-chain", null, null, null, null, null, "ethereum",
                "0x" + address.substring(2).toUpperCase()).type()).isEqualTo("on_chain");
        assertThat(new Destination("on_chain", null, null, null, null, null, "base",
                "0x" + address.substring(2).toUpperCase()).address()).isEqualTo(
                "0x" + address.substring(2).toUpperCase());
        assertThatThrownBy(() -> new Destination("on_chain", null, null, null, null, null,
                "solana", address))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("unknown on-chain network");
        assertThatThrownBy(() -> new Destination("on_chain", null, null, null, null, null, null,
                address))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("network is required");
        for (String bad : new String[]{"0x123", "8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d",
                "0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9dz"}) {
            assertThatThrownBy(() -> new Destination("on_chain", null, null, null, null, null,
                    "base", bad))
                    .as("address %s", bad)
                    .isInstanceOf(UnsupportedDestinationException.class)
                    .hasMessageContaining("EVM address");
        }
        assertThatThrownBy(() -> new Destination("on_chain", null, null, null, null, null, "base",
                null))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("address is required");
    }

    @Test
    void unknownDestinationTypesAreRejected() {
        assertThatThrownBy(() -> new Destination("paypal", null, null, null, null, null, null,
                null))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("unknown destination type");
        assertThatThrownBy(() -> new Destination(null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("destination type is required");
    }

    @Test
    void describeRendersARedactedSingleLineAuditString() {
        assertThat(new Destination("mpesa", "+254712345678", null, null, null, null, null, null)
                .describe()).isEqualTo("mpesa:+254712345678");
        assertThat(new Destination("bank", null, " KCB ", " ACC-1 ", null, null, null, null)
                .describe()).isEqualTo("bank:KCB:ACC-1");
        assertThat(new Destination("on_chain", null, null, null, null, null, "base",
                "0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d").describe())
                .isEqualTo("on_chain:base:0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d");
    }
}
