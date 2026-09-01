package com.sharkpay.money;

/** Allocation ratios are empty, negative, or do not sum to the given total. */
public class InvalidRatiosException extends MoneyException {

    public InvalidRatiosException(String detail) {
        super("invalid ratios: " + detail);
    }
}
