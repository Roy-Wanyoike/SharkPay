package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.money.Currencies;
import com.sharkpay.risk.domain.exceptions.InvalidEvaluationException;

import java.util.Locale;

/**
 * Canonical money JSON {@code {"amount_minor": long, "currency": "KES",
 * "exponent": 2}} (docs/API-CONTRACTS.md 1.6, common.yaml Money schema).
 * Input validation happens in the canonical constructor so a bad body fails
 * as 400 regardless of the transport that produced it.
 */
public record MoneyDto(
        @JsonProperty("amount_minor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("exponent") Integer exponent) {

    public MoneyDto {
        if (amountMinor < 1) {
            throw new InvalidEvaluationException("amount_minor must be a positive integer, got " + amountMinor);
        }
        if (currency == null || currency.isBlank()) {
            throw new InvalidEvaluationException("amount.currency must not be blank");
        }
        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (!Currencies.isSupported(currency)) {
            throw new InvalidEvaluationException("amount.currency is not a supported currency: '" + currency + "'");
        }
        if (exponent != null && exponent != Currencies.exponentFor(currency)) {
            throw new InvalidEvaluationException(
                    "amount.exponent " + exponent + " does not match currency " + currency
                            + " (expected " + Currencies.exponentFor(currency) + ")");
        }
        exponent = Currencies.exponentFor(currency);
    }

    public com.sharkpay.money.Money toMoney() {
        return com.sharkpay.money.Money.of(amountMinor, currency);
    }
}
