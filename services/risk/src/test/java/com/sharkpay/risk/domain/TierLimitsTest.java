package com.sharkpay.risk.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierLimitsTest {

    @Test
    void holdsCapsAndReportsTheOptionalSingleLimit() {
        TierLimits withoutSingle = new TierLimits(Money.of(5_000_00L, "KES"), Money.of(20_000_00L, "KES"), null);
        assertThat(withoutSingle.dailyCap()).isEqualTo(Money.of(5_000_00L, "KES"));
        assertThat(withoutSingle.weeklyCap()).isEqualTo(Money.of(20_000_00L, "KES"));
        assertThat(withoutSingle.maxSingleLimit()).isEqualTo(Optional.empty());

        TierLimits withSingle = new TierLimits(Money.of(500_000_00L, "KES"),
                Money.of(2_000_000_00L, "KES"), Money.of(150_000_00L, "KES"));
        assertThat(withSingle.maxSingleLimit()).contains(Money.of(150_000_00L, "KES"));
    }

    @Test
    void zeroCapsAreLegal() {
        TierLimits zero = new TierLimits(Money.zero("KES"), Money.zero("KES"), null);
        assertThat(zero.dailyCap().isZero()).isTrue();
        assertThat(zero.weeklyCap().isZero()).isTrue();
    }

    @Test
    void capsMustShareOneCurrency() {
        assertThatThrownBy(() -> new TierLimits(Money.of(1_00L, "KES"), Money.of(1_00L, "USD"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one currency");
        assertThatThrownBy(() -> new TierLimits(Money.of(1_00L, "KES"), Money.of(9_00L, "KES"),
                Money.of(1_00L, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSingle");
    }

    @Test
    void capsMustNotBeNegative() {
        assertThatThrownBy(() -> new TierLimits(Money.of(-1L, "KES"), Money.of(9_00L, "KES"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new TierLimits(Money.of(1_00L, "KES"), Money.of(-9L, "KES"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new TierLimits(Money.of(1_00L, "KES"), Money.of(9_00L, "KES"),
                Money.of(-1L, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void nullCapsAreRejected() {
        assertThatThrownBy(() -> new TierLimits(null, Money.of(9_00L, "KES"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dailyCap");
        assertThatThrownBy(() -> new TierLimits(Money.of(1_00L, "KES"), null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("weeklyCap");
    }

    @Test
    void velocityPolicyValidatesItsBounds() {
        assertThatThrownBy(() -> new VelocityPolicy(0, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTransactions");
        assertThatThrownBy(() -> new VelocityPolicy(1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new VelocityPolicy(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new VelocityPolicy(1, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");

        VelocityPolicy policy = new VelocityPolicy(10, Duration.ofHours(1));
        assertThat(policy.maxTransactions()).isEqualTo(10);
        assertThat(policy.window()).isEqualTo(Duration.ofHours(1));
    }
}
