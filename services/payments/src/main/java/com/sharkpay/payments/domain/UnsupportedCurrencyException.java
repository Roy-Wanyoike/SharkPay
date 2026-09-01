package com.sharkpay.payments.domain;

import com.sharkpay.money.Money;

/**
 * The intent cannot collect in the requested currency (unknown code, or no
 * fee schedule serves the rail/currency pair). Business rejection: 422
 * {@code unsupported_currency} (payments.yaml 422 codes).
 */
public class UnsupportedCurrencyException extends PaymentDomainException {

    private final String currency;
    private final Rail rail;

    public UnsupportedCurrencyException(String currency) {
        this(currency, null);
    }

    public UnsupportedCurrencyException(String currency, Rail rail) {
        super("currency " + currency + " cannot be collected"
                + (rail == null ? "" : " on rail " + rail.wireName()));
        this.currency = currency;
        this.rail = rail;
    }

    public String currency() {
        return currency;
    }

    public Rail rail() {
        return rail;
    }
}
