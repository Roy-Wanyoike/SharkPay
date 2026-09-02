package com.sharkpay.payouts.domain;

import com.sharkpay.money.Money;
import com.sharkpay.money.MoneyOverflowException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PayoutFeePolicy: pure integer arithmetic (ADR 003 G2 — no floats):
 * {@code fee = flatMinor + floor(amountMinor * bps / 10_000)}, the flat
 * component is the non-refundable portion (capped at the total fee), and
 * any overflow surfaces as {@link MoneyOverflowException} (422
 * money_overflow) — never a silent wrap.
 */
class PayoutFeePolicyTest {

    @Test
    void theDefaultScheduleQuotesMpesaExactly() {
        PayoutFeePolicy.Quote quote = PayoutFeePolicy.defaults().quote(Rail.MPESA,
                Money.of(500_000, "KES"));
        // flat 5500 + 1% of 500000 = 5500 + 5000
        assertThat(quote.fee()).isEqualTo(Money.of(10_500, "KES"));
        // the flat part is non-refundable, capped at the total fee
        assertThat(quote.nonRefundable()).isEqualTo(Money.of(5_500, "KES"));
    }

    @Test
    void theDefaultScheduleQuotesBankAndOnChainExactly() {
        PayoutFeePolicy defaults = PayoutFeePolicy.defaults();
        assertThat(defaults.quote(Rail.BANK, Money.of(1_000_000, "KES")).fee())
                .isEqualTo(Money.of(3_000 + 5_000, "KES"));
        assertThat(defaults.quote(Rail.ON_CHAIN, Money.of(25_000_000, "USDC")).fee())
                .isEqualTo(Money.of(250_000 + 62_500, "USDC"));
    }

    @Test
    void bpsUseFloorDivisionOfTheExactProduct() {
        // 10001 * 100 / 10000 = 100.01 → floor 100
        PayoutFeePolicy zeroFlat = new PayoutFeePolicy(Map.of(
                Rail.MPESA, new PayoutFeePolicy.RailFee(0, 100),
                Rail.BANK, new PayoutFeePolicy.RailFee(0, 100),
                Rail.ON_CHAIN, new PayoutFeePolicy.RailFee(0, 100)));
        assertThat(zeroFlat.quote(Rail.MPESA, Money.of(10_001, "KES")).fee())
                .isEqualTo(Money.of(100, "KES"));
        // 9999 * 100 / 10000 = 99.99 → floor 99
        assertThat(zeroFlat.quote(Rail.MPESA, Money.of(9_999, "KES")).fee())
                .isEqualTo(Money.of(99, "KES"));
    }

    @Test
    void zeroBpsYieldsTheFlatFeeOnlyAndZeroBpsShortCircuitsTheProduct() {
        PayoutFeePolicy flatOnly = new PayoutFeePolicy(Map.of(
                Rail.MPESA, new PayoutFeePolicy.RailFee(7_000, 0),
                Rail.BANK, new PayoutFeePolicy.RailFee(3_000, 0),
                Rail.ON_CHAIN, new PayoutFeePolicy.RailFee(250_000, 0)));
        assertThat(flatOnly.quote(Rail.MPESA, Money.of(123_457, "KES")).fee())
                .isEqualTo(Money.of(7_000, "KES"));
        // a full-bps amount cannot overflow the multiply when bps is zero
        assertThat(flatOnly.quote(Rail.ON_CHAIN, Money.of(Long.MAX_VALUE, "USDC")).fee())
                .isEqualTo(Money.of(250_000, "USDC"));
    }

    @Test
    void theNonRefundablePortionIsCappedAtTheTotalFee() {
        // bps dominates: fee = 100 + 1000 = 1100 < flat 5000 → non-refundable = 1100
        PayoutFeePolicy policy = new PayoutFeePolicy(Map.of(
                Rail.MPESA, new PayoutFeePolicy.RailFee(5_000, 1_000),
                Rail.BANK, new PayoutFeePolicy.RailFee(0, 0),
                Rail.ON_CHAIN, new PayoutFeePolicy.RailFee(0, 0)));
        PayoutFeePolicy.Quote quote = policy.quote(Rail.MPESA, Money.of(10_000, "KES"));
        assertThat(quote.fee()).isEqualTo(Money.of(6_000, "KES"));
        assertThat(quote.nonRefundable()).isEqualTo(Money.of(5_000, "KES"));
        // zero flat → zero non-refundable
        assertThat(policy.quote(Rail.BANK, Money.of(10_000, "KES")).nonRefundable())
                .isEqualTo(Money.zero("KES"));
    }

    @Test
    void theQuoteCarriesThePayoutCurrencyAndExponent() {
        PayoutFeePolicy.Quote quote = PayoutFeePolicy.defaults().quote(Rail.ON_CHAIN,
                Money.of(25_000_000, "USDC"));
        assertThat(quote.fee().currency()).isEqualTo("USDC");
        assertThat(quote.fee().exponent()).isEqualTo(6);
        assertThat(quote.nonRefundable().currency()).isEqualTo("USDC");
    }

    @Test
    void amountTimesBpsOverflowSurfacesAsMoneyOverflow() {
        PayoutFeePolicy policy = new PayoutFeePolicy(Map.of(
                Rail.MPESA, new PayoutFeePolicy.RailFee(0, 100),
                Rail.BANK, new PayoutFeePolicy.RailFee(0, 100),
                Rail.ON_CHAIN, new PayoutFeePolicy.RailFee(0, 100)));
        assertThatThrownBy(() -> policy.quote(Rail.MPESA, Money.of(Long.MAX_VALUE, "KES")))
                .isInstanceOf(MoneyOverflowException.class)
                .hasMessageContaining("fee computation overflow");
    }

    @Test
    void feeTotalOverflowSurfacesAsMoneyOverflow() {
        PayoutFeePolicy policy = new PayoutFeePolicy(Map.of(
                Rail.MPESA, new PayoutFeePolicy.RailFee(Long.MAX_VALUE, 10_000),
                Rail.BANK, new PayoutFeePolicy.RailFee(0, 0),
                Rail.ON_CHAIN, new PayoutFeePolicy.RailFee(0, 0)));
        // flat = MAX, bps 100% of 2 minor = 2 → MAX + 2 overflows the total
        assertThatThrownBy(() -> policy.quote(Rail.MPESA, Money.of(2, "KES")))
                .isInstanceOf(MoneyOverflowException.class)
                .hasMessageContaining("fee total overflow");
    }

    @Test
    void railFeeComponentsAreValidatedAtConstruction() {
        assertThatThrownBy(() -> new PayoutFeePolicy.RailFee(-1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flat fee must be non-negative");
        assertThatThrownBy(() -> new PayoutFeePolicy.RailFee(100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bps must be within [0, 10000]");
        assertThatThrownBy(() -> new PayoutFeePolicy.RailFee(100, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PayoutFeePolicy.RailFee(0, 10_000).bps()).isEqualTo(10_000); // boundary ok
    }

    @Test
    void theScheduleMustCoverEveryRail() {
        Map<Rail, PayoutFeePolicy.RailFee> incomplete = new HashMap<>(Map.of(
                Rail.MPESA, new PayoutFeePolicy.RailFee(1, 1),
                Rail.BANK, new PayoutFeePolicy.RailFee(1, 1)));
        assertThatThrownBy(() -> new PayoutFeePolicy(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing rail on_chain");
        assertThatThrownBy(() -> new PayoutFeePolicy(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void quoteArgumentsAreNullChecked() {
        PayoutFeePolicy defaults = PayoutFeePolicy.defaults();
        assertThatThrownBy(() -> defaults.quote(null, Money.of(1, "KES")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rail is required");
        assertThatThrownBy(() -> defaults.quote(Rail.MPESA, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount is required");
    }

    @Test
    void theQuoteRecordValidatesItsOwnInvariants() {
        Money one = Money.of(1, "KES");
        assertThatThrownBy(() -> new PayoutFeePolicy.Quote(one, Money.zero("USD")))
                .isInstanceOf(com.sharkpay.money.CurrencyMismatchException.class);
        assertThatThrownBy(() -> new PayoutFeePolicy.Quote(one, Money.of(2, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-refundable fee must be within [0, fee]");
        assertThatThrownBy(() -> new PayoutFeePolicy.Quote(one, Money.of(-1, "KES")))
                .isInstanceOf(IllegalArgumentException.class);
        // equal is the boundary and is legal
        assertThat(new PayoutFeePolicy.Quote(one, one).nonRefundable()).isEqualTo(one);
    }
}
