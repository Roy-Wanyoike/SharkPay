package com.sharkpay.payments.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import com.sharkpay.money.MoneyOverflowException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exact bps fee math (PRD fee schedule, G2): integer-only shares via
 * largest-remainder allocation, deterministic rounding remainder, clamps and
 * hard overflow rejection — never a float, never a wrap.
 */
class FeePolicyTest {

    @Test
    void computesExactBpsFeesWithoutFloats() {
        FeePolicy honeycoinKes = new FeePolicy(Rail.HONEYCOIN, "KES", 50, 0, 0, null);
        assertThat(honeycoinKes.computeFee(Money.of(150_000, "KES")).amountMinor()).isEqualTo(750L);
        assertThat(honeycoinKes.computeFee(Money.of(1_000, "KES")).amountMinor()).isEqualTo(5L);
        assertThat(honeycoinKes.computeFee(Money.of(2_000, "KES")).amountMinor()).isEqualTo(10L);

        FeePolicy mpesaKes = new FeePolicy(Rail.MPESA, "KES", 250, 0, 0, 10_000L);
        assertThat(mpesaKes.computeFee(Money.of(10_000, "KES")).amountMinor()).isEqualTo(250L);
    }

    @Test
    void roundingRemainderGoesToTheLargerFractionalPart() {
        // 999 @ 50 bps = 4.995 minor units: floor 4, the single leftover unit
        // belongs to the larger fractional remainder (.995 > .005) → fee 5.
        FeePolicy policy = new FeePolicy(Rail.HONEYCOIN, "KES", 50, 0, 0, null);
        assertThat(policy.computeFee(Money.of(999, "KES")).amountMinor()).isEqualTo(5L);
        // lossless: fee + remainder share == amount
        Money amount = Money.of(999, "KES");
        Money[] split = amount.allocate(new int[]{50, 9_950}, 10_000);
        assertThat(split[0].add(split[1])).isEqualTo(amount);
        assertThat(split[0].amountMinor()).isEqualTo(5L);
        // 1_001 @ 50 bps = 5.005 → floor 5, remainder .005 < .995 → stays 5
        assertThat(policy.computeFee(Money.of(1_001, "KES")).amountMinor()).isEqualTo(5L);
    }

    @Test
    void remainderTieGoesToTheFeePartAtLowerIndex() {
        // 50/10000 of an amount where both fractional remainders are equal
        // (e.g. amount 199: 0.995 vs 198.005 — not a tie, but prove the
        // tie rule with 1/2 of 3 = 1.5/1.5): allocate([1,1], 2) on 3 → 2/1,
        // lower index (the fee part) takes the extra unit.
        Money[] split = Money.of(3, "KES").allocate(new int[]{1, 1}, 2);
        assertThat(split[0].amountMinor()).isEqualTo(2L);
        assertThat(split[1].amountMinor()).isEqualTo(1L);
    }

    @Test
    void fixedComponentIsAddedExactly() {
        FeePolicy policy = new FeePolicy(Rail.BANK, "USD", 30, 25, 0, null);
        // 10_000 @ 30 bps = 30 + fixed 25 = 55
        assertThat(policy.computeFee(Money.of(10_000, "USD")).amountMinor()).isEqualTo(55L);
    }

    @Test
    void minimumClampAppliesAfterTheSum() {
        FeePolicy policy = new FeePolicy(Rail.BANK, "KES", 30, 0, 500, null);
        assertThat(policy.computeFee(Money.of(100, "KES")).amountMinor()).isEqualTo(500L);
        // above the clamp the exact bps share wins (30 bps of 200 000 = 600)
        assertThat(policy.computeFee(Money.of(200_000, "KES")).amountMinor()).isEqualTo(600L);
    }

    @Test
    void maximumClampCapsTheFee() {
        FeePolicy policy = new FeePolicy(Rail.MPESA, "KES", 250, 0, 0, 5_000L);
        assertThat(policy.computeFee(Money.of(1_000_000, "KES")).amountMinor()).isEqualTo(5_000L);
    }

    @Test
    void overflowIsRejectedNeverWrapped() {
        // bps share (10 000 bps = the whole amount) + a fixed component
        // summing past Long.MAX_VALUE must be rejected, not wrapped
        FeePolicy policy = new FeePolicy(Rail.HONEYCOIN, "KES", 10_000, Long.MAX_VALUE / 2,
                0, null);
        assertThatThrownBy(() -> policy.computeFee(Money.of(Long.MAX_VALUE / 2 + 2, "KES")))
                .isInstanceOf(MoneyOverflowException.class);
    }

    @Test
    void zeroBpsYieldsTheFixedOrMinimumOnly() {
        assertThat(new FeePolicy(Rail.HONEYCOIN, "KES", 0, 0, 0, null)
                .computeFee(Money.of(1_000, "KES")).amountMinor()).isZero();
        assertThat(new FeePolicy(Rail.HONEYCOIN, "KES", 0, 7, 0, null)
                .computeFee(Money.of(1_000, "KES")).amountMinor()).isEqualTo(7L);
    }

    @Test
    void currencyMustMatchTheAmount() {
        FeePolicy policy = new FeePolicy(Rail.HONEYCOIN, "KES", 50, 0, 0, null);
        assertThatThrownBy(() -> policy.computeFee(Money.of(1_000, "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void rejectsNonPositiveAmountsAndInvalidPolicies() {
        FeePolicy policy = new FeePolicy(Rail.HONEYCOIN, "KES", 50, 0, 0, null);
        assertThatThrownBy(() -> policy.computeFee(Money.zero("KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> policy.computeFee(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive amounts only");

        assertThatThrownBy(() -> new FeePolicy(Rail.HONEYCOIN, "KES", -1, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bps");
        assertThatThrownBy(() -> new FeePolicy(Rail.HONEYCOIN, "KES", 10_001, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bps");
        assertThatThrownBy(() -> new FeePolicy(Rail.HONEYCOIN, "KES", 50, -1, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-negative");
        assertThatThrownBy(() -> new FeePolicy(Rail.HONEYCOIN, "KES", 50, 0, -1, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-negative");
        assertThatThrownBy(() -> new FeePolicy(Rail.HONEYCOIN, "KES", 50, 0, 10, 5L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximum");
        assertThatThrownBy(() -> new FeePolicy(Rail.HONEYCOIN, "KES", 50, 0, 0, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximum");
    }

    @Test
    void toStringCarriesTheScheduleForAuditLogs() {
        assertThat(new FeePolicy(Rail.MPESA, "KES", 250, 0, 100, 5_000L).toString())
                .contains("mpesa").contains("KES").contains("bps=250").contains("min=100")
                .contains("max=5000");
    }
}
