package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two statement-line records (the canonical shapes the port adapters
 * map onto): mandatory fields, and the matchability rule that separates an
 * internal movement the provider knows about from one it does not.
 */
class StatementLinesTest {

    private static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");

    @Test
    void aProviderLineCarriesTheRawWireStatus() {
        ProviderStatementLine line = new ProviderStatementLine("hc_tr_8842", "SUCCEEDED",
                Money.of(150_000, "KES"), Money.of(500, "KES"), T0);
        assertThat(line.ref()).isEqualTo("hc_tr_8842");
        assertThat(line.status()).isEqualTo("SUCCEEDED"); // raw — mapped later, never guessed
        assertThat(line.amount()).isEqualTo(Money.of(150_000, "KES"));
        assertThat(line.fee()).isEqualTo(Money.of(500, "KES"));
        assertThat(line.occurredAt()).isEqualTo(T0);
    }

    @Test
    void providerLineValidatesItsMandatoryFields() {
        assertThatThrownBy(() -> new ProviderStatementLine(null, "CONFIRMED",
                Money.of(1, "KES"), Money.of(0, "KES"), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ref is required");
        assertThatThrownBy(() -> new ProviderStatementLine(" ", "CONFIRMED",
                Money.of(1, "KES"), Money.of(0, "KES"), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ref must not be blank");
        assertThatThrownBy(() -> new ProviderStatementLine("hc_1", null,
                Money.of(1, "KES"), Money.of(0, "KES"), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status is required");
        assertThatThrownBy(() -> new ProviderStatementLine("hc_1", "CONFIRMED",
                null, Money.of(0, "KES"), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount is required");
        assertThatThrownBy(() -> new ProviderStatementLine("hc_1", "CONFIRMED",
                Money.of(1, "KES"), null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fee is required");
        assertThatThrownBy(() -> new ProviderStatementLine("hc_1", "CONFIRMED",
                Money.of(1, "KES"), Money.of(0, "KES"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt is required");
    }

    @Test
    void anInternalLineIsMatchableOnlyWhenItCarriesAProviderRef() {
        InternalLedgerLine withRef = new InternalLedgerLine("int_1", "hc_1", "CONFIRMED",
                Money.of(150_000, "KES"), Money.of(500, "KES"), T0);
        assertThat(withRef.isMatchable()).isTrue();

        InternalLedgerLine withoutRef = new InternalLedgerLine("int_2", null, "CONFIRMED",
                Money.of(150_000, "KES"), Money.of(500, "KES"), T0);
        assertThat(withoutRef.isMatchable()).isFalse();

        InternalLedgerLine blankRef = new InternalLedgerLine("int_3", "  ", "CONFIRMED",
                Money.of(150_000, "KES"), Money.of(500, "KES"), T0);
        assertThat(blankRef.isMatchable()).isFalse();
    }

    @Test
    void internalLineValidatesItsMandatoryFields() {
        assertThatThrownBy(() -> new InternalLedgerLine(null, "hc_1", "CONFIRMED",
                Money.of(1, "KES"), Money.of(0, "KES"), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("internalRef is required");
        assertThatThrownBy(() -> new InternalLedgerLine(" ", "hc_1", "CONFIRMED",
                Money.of(1, "KES"), Money.of(0, "KES"), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internalRef must not be blank");
        assertThatThrownBy(() -> new InternalLedgerLine("int_1", "hc_1", null,
                Money.of(1, "KES"), Money.of(0, "KES"), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status is required");
        assertThatThrownBy(() -> new InternalLedgerLine("int_1", "hc_1", "CONFIRMED",
                null, Money.of(0, "KES"), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount is required");
        assertThatThrownBy(() -> new InternalLedgerLine("int_1", "hc_1", "CONFIRMED",
                Money.of(1, "KES"), null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fee is required");
        assertThatThrownBy(() -> new InternalLedgerLine("int_1", "hc_1", "CONFIRMED",
                Money.of(1, "KES"), Money.of(0, "KES"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt is required");
    }

    @Test
    void detectedBreakCarriesBothSidesFactsVerbatim() {
        DetectedBreak detected = new DetectedBreak(BreakType.MISSING_INTERNAL, "hc_9", null,
                Money.of(9_000, "KES"), null, Money.of(0, "KES"), null, "CONFIRMED", null);
        assertThat(detected.breakType()).isEqualTo(BreakType.MISSING_INTERNAL);
        assertThat(detected.providerRef()).isEqualTo("hc_9");
        assertThat(detected.internalRef()).isNull();
        assertThat(detected.providerAmount()).isEqualTo(Money.of(9_000, "KES"));
        assertThat(detected.internalAmount()).isNull();
        assertThat(detected.internalStatus()).isNull();
    }
}
