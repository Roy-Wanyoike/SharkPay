package com.sharkpay.money;

/** A currency code is not in the supported table (KES USD EUR GBP USDC USDT). */
public class UnknownCurrencyException extends MoneyException {

    public UnknownCurrencyException(String currency) {
        super("unknown currency: \"" + currency + "\"");
    }
}
