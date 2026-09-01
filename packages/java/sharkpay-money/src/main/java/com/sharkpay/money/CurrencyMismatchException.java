package com.sharkpay.money;

/** Two monies of different currencies were combined or compared. */
public class CurrencyMismatchException extends MoneyException {

    public CurrencyMismatchException(String left, String right) {
        super("currency mismatch: " + left + " vs " + right);
    }
}
