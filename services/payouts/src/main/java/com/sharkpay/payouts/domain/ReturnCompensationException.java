package com.sharkpay.payouts.domain;

import com.sharkpay.money.Money;

/**
 * A provider return cannot be compensated (422 return_compensation_impossible
 * + ops case): the returned amount minus the non-refundable fee would be
 * negative, the return currency mismatches, or the payout never reached a
 * returnable state. No ledger posting is made — money is never moved on an
 * uncomputable compensation.
 */
public class ReturnCompensationException extends PayoutsDomainException {

    public enum Reason { NEGATIVE_COMPENSATION, CURRENCY_MISMATCH, NOT_RETURNABLE }

    private final String payoutId;
    private final Reason reason;

    public ReturnCompensationException(String payoutId, Reason reason, String message) {
        super(message);
        this.payoutId = payoutId;
        this.reason = reason;
    }

    public static ReturnCompensationException negative(String payoutId, Money returned,
                                                       Money nonRefundableFee) {
        return new ReturnCompensationException(payoutId, Reason.NEGATIVE_COMPENSATION,
                "return compensation for payout " + payoutId + " would be negative: returned "
                        + returned.amountMinor() + " " + returned.currency()
                        + " minus non-refundable fee " + nonRefundableFee.amountMinor()
                        + " — ops case required");
    }

    public static ReturnCompensationException currencyMismatch(String payoutId, String expected,
                                                               String actual) {
        return new ReturnCompensationException(payoutId, Reason.CURRENCY_MISMATCH,
                "return for payout " + payoutId + " reports currency " + actual
                        + " but the payout is denominated in " + expected);
    }

    public static ReturnCompensationException notReturnable(String payoutId, PayoutState state) {
        return new ReturnCompensationException(payoutId, Reason.NOT_RETURNABLE,
                "payout " + payoutId + " is in state " + state.wireName()
                        + " and cannot accept a return");
    }

    public String payoutId() {
        return payoutId;
    }

    public Reason reason() {
        return reason;
    }
}
