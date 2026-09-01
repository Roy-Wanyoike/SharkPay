package com.sharkpay.payments.domain;

import com.sharkpay.money.Money;

/**
 * Domain fee computation policy (PRD "fee schedule per rail/currency"):
 * {@code fee = bps·amount/10 000 + fixed}, clamped to
 * {@code [minimum, maximum]}, all in exact integer minor units via
 * sharkpay-money — the bps share is split out with the money library's
 * largest-remainder allocation (never a float), the split is lossless, and
 * any overflow of the sum is rejected (MoneyOverflowException → 422).
 *
 * <p>Rounding remainder policy: the bps share uses
 * {@link Money#allocate(int[], int) allocate([bps, 10 000 − bps], 10 000)} —
 * floor plus largest-remainder, deterministic; with two parts the at-most-one
 * leftover minor unit goes to the fee part when its fractional remainder is
 * the largest (ties: lower index = fee). Documented and tested in
 * {@code FeePolicyTest}.</p>
 *
 * @param rail            rail the fee prices
 * @param currency        currency of the fee (must match the amount's)
 * @param bps             fee in basis points of the amount (0..10 000)
 * @param fixedMinor      flat component, minor units (≥ 0)
 * @param minimumMinor    lower clamp, minor units (≥ 0, applied after the sum)
 * @param maximumMinor    upper clamp, minor units (null = unbounded)
 */
public record FeePolicy(Rail rail, String currency, int bps, long fixedMinor,
                        long minimumMinor, Long maximumMinor) {

    public FeePolicy {
        if (bps < 0 || bps > 10_000) {
            throw new IllegalArgumentException("fee bps must be within [0, 10000]: " + bps);
        }
        if (fixedMinor < 0 || minimumMinor < 0) {
            throw new IllegalArgumentException("fee fixed/minimum components must be non-negative");
        }
        if (maximumMinor != null && maximumMinor < minimumMinor) {
            throw new IllegalArgumentException("fee maximum must be >= minimum");
        }
        if (maximumMinor != null && maximumMinor < 0) {
            throw new IllegalArgumentException("fee maximum must be non-negative");
        }
    }

    /**
     * Computes the fee for {@code amount} (positive, currency == this
     * schedule's currency).
     *
     * @throws MoneyOverflowException (money lib) when the bps share + fixed
     *         component does not fit a long minor-unit amount — overflow is
     *         rejected, never wrapped
     */
    public Money computeFee(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("fee applies to positive amounts only: " + amount);
        }
        if (!amount.currency().equals(currency)) {
            throw new com.sharkpay.money.CurrencyMismatchException(currency, amount.currency());
        }
        Money bpsShare = amount.allocate(new int[]{bps, 10_000 - bps}, 10_000)[0];
        Money fee = bpsShare.add(Money.of(fixedMinor, currency));
        if (fee.amountMinor() < minimumMinor) {
            fee = Money.of(minimumMinor, currency);
        }
        if (maximumMinor != null && fee.amountMinor() > maximumMinor) {
            fee = Money.of(maximumMinor, currency);
        }
        return fee;
    }

    @Override
    public String toString() {
        return "FeePolicy[" + rail.wireName() + " " + currency + " bps=" + bps + " fixed=" + fixedMinor
                + " min=" + minimumMinor + " max=" + maximumMinor + "]";
    }
}
