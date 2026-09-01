package com.sharkpay.money;

/** A money computation exceeded the signed 64-bit minor-unit range. */
public class MoneyOverflowException extends MoneyException {

    public MoneyOverflowException(String detail) {
        super("money overflow: " + detail);
    }

    public MoneyOverflowException(String detail, Throwable cause) {
        super("money overflow: " + detail, cause);
    }
}
