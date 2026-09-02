package com.sharkpay.payouts.domain;

import com.sharkpay.money.Money;
import com.sharkpay.money.MoneyOverflowException;

import java.util.Map;
import java.util.Objects;

/**
 * Rail fee schedule for payouts, pure integer arithmetic (no floats, ADR 003
 * G2): per rail, a flat minor-unit component plus a per-myriad (bps)
 * component of the payout amount,
 *
 * <pre>fee = flatMinor + floor(amountMinor * bps / 10_000)</pre>
 *
 * The flat component is the <b>non-refundable</b> portion retained by the
 * rail on a RETURNED payout (capped at the total fee). Both components are
 * denominated in the payout currency's minor units; production swaps this
 * for a per-currency table without touching the call sites.
 *
 * <p>Overflow: {@code amountMinor * bps} uses exact multiplication and
 * surfaces as {@link MoneyOverflowException} (422 money_overflow) — never a
 * silent wrap.</p>
 */
public final class PayoutFeePolicy {

    /** Fee components for one rail. */
    public record RailFee(long flatMinor, int bps) {

        public RailFee {
            if (flatMinor < 0) {
                throw new IllegalArgumentException("flat fee must be non-negative: " + flatMinor);
            }
            if (bps < 0 || bps > 10_000) {
                throw new IllegalArgumentException("bps must be within [0, 10000]: " + bps);
            }
        }
    }

    private final Map<Rail, RailFee> schedule;

    public PayoutFeePolicy(Map<Rail, RailFee> schedule) {
        Objects.requireNonNull(schedule, "schedule is required");
        for (Rail rail : Rail.values()) {
            if (!schedule.containsKey(rail)) {
                throw new IllegalArgumentException("fee schedule is missing rail " + rail);
            }
        }
        this.schedule = Map.copyOf(schedule);
    }

    /**
     * Default V1 schedule (minor units of the payout currency):
     * mpesa 5.50 flat + 1% · bank 3.00 flat + 0.5% · on-chain 0.25 + 0.25%.
     */
    public static PayoutFeePolicy defaults() {
        return new PayoutFeePolicy(Map.of(
                Rail.MPESA, new RailFee(5_500, 100),
                Rail.BANK, new RailFee(3_000, 50),
                Rail.ON_CHAIN, new RailFee(250_000, 25)));
    }

    /**
     * Quotes the payout fee for {@code amount} on {@code rail}: the total
     * fee charged at hold time and the non-refundable portion retained on a
     * return.
     */
    public Quote quote(Rail rail, Money amount) {
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(amount, "amount is required");
        RailFee railFee = schedule.get(rail);
        long feeMinor = addExact(railFee.flatMinor(), bpsPart(railFee, amount));
        Money fee = Money.of(feeMinor, amount.currency());
        long nonRefundableMinor = Math.min(railFee.flatMinor(), feeMinor);
        return new Quote(fee, Money.of(nonRefundableMinor, amount.currency()));
    }

    private static long bpsPart(RailFee railFee, Money amount) {
        if (railFee.bps() == 0) {
            return 0L;
        }
        try {
            // floor division of the exact product — deterministic, lossless
            return Math.multiplyExact(amount.amountMinor(), (long) railFee.bps()) / 10_000L;
        } catch (ArithmeticException overflow) {
            throw new MoneyOverflowException("fee computation overflow for "
                    + amount.amountMinor() + " " + amount.currency() + " at " + railFee.bps()
                    + "bps", overflow);
        }
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new MoneyOverflowException("fee total overflow (" + left + " + " + right + ")",
                    overflow);
        }
    }

    /**
     * A quoted fee: the total fee and its non-refundable portion (0 ≤
     * non-refundable ≤ fee, same currency as the amount).
     */
    public record Quote(Money fee, Money nonRefundable) {

        public Quote {
            Objects.requireNonNull(fee, "fee is required");
            Objects.requireNonNull(nonRefundable, "nonRefundable is required");
            if (!fee.currency().equals(nonRefundable.currency())) {
                throw new com.sharkpay.money.CurrencyMismatchException(fee.currency(),
                        nonRefundable.currency());
            }
            if (nonRefundable.isNegative() || nonRefundable.amountMinor() > fee.amountMinor()) {
                throw new IllegalArgumentException(
                        "non-refundable fee must be within [0, fee]: " + nonRefundable);
            }
        }
    }
}
