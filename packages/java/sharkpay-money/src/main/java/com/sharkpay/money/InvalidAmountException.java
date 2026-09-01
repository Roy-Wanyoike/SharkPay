package com.sharkpay.money;

/** A string is not a valid decimal amount for the target currency. */
public class InvalidAmountException extends MoneyException {

    public InvalidAmountException(String raw) {
        super("\"" + raw + "\" is not a valid decimal amount");
    }

    public InvalidAmountException(String message, boolean detail) {
        super(detail ? message : message);
    }
}
