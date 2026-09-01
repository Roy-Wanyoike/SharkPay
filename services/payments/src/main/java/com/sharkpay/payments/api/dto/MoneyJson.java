package com.sharkpay.payments.api.dto;

import com.sharkpay.money.Money;

/**
 * Canonical money JSON (docs/API-CONTRACTS.md §1.6):
 * {@code {"amount_minor": <int64>, "currency": "<code>", "exponent": <int>}}.
 * Integer minor units only — never a binary-fraction type. Record component
 * names ARE the JSON field names.
 */
public record MoneyJson(long amount_minor, String currency, int exponent) {

    public static MoneyJson of(Money money) {
        return new MoneyJson(money.amountMinor(), money.currency(), money.exponent());
    }
}
