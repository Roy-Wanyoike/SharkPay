package com.sharkpay.risk.domain;

import com.sharkpay.money.Money;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-policy amount caps. All caps of one policy share a single currency
 * (default KES) — cross-currency limit checks are deferred to the
 * integration phase and documented as such by {@code LimitRule}.
 *
 * <ul>
 *   <li>{@code dailyCap} — rolling 24h sum of allowed transactions + the
 *       request amount must not exceed this.</li>
 *   <li>{@code weeklyCap} — rolling 7d equivalent.</li>
 *   <li>{@code maxSingle} — optional single-transaction cap (agent policy).</li>
 * </ul>
 */
public record TierLimits(Money dailyCap, Money weeklyCap, Money maxSingle) {

    public TierLimits {
        Objects.requireNonNull(dailyCap, "dailyCap must not be null");
        Objects.requireNonNull(weeklyCap, "weeklyCap must not be null");
        if (!dailyCap.currency().equals(weeklyCap.currency())) {
            throw new IllegalArgumentException(
                    "tier limits must share one currency: daily " + dailyCap.currency()
                            + " vs weekly " + weeklyCap.currency());
        }
        if (maxSingle != null && !maxSingle.currency().equals(dailyCap.currency())) {
            throw new IllegalArgumentException("maxSingle cap currency must match the policy currency");
        }
        if (dailyCap.isNegative() || weeklyCap.isNegative() || (maxSingle != null && maxSingle.isNegative())) {
            throw new IllegalArgumentException("caps must not be negative");
        }
    }

    /** Single-transaction cap when the policy defines one. */
    public Optional<Money> maxSingleLimit() {
        return Optional.ofNullable(maxSingle);
    }
}
