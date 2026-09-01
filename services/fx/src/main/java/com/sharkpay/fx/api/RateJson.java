package com.sharkpay.fx.api;

import com.sharkpay.fx.domain.Rate;

/**
 * Canonical rate JSON (contracts/openapi/v1/fx.yaml): the exchange rate as
 * {@code value_minor × 10^-exponent} quote-currency minor units per one
 * base-currency major unit — an exact integer representation, never a
 * binary-fraction type. Example: value_minor 12900, exponent 0 means 12900
 * KES-minor (129.00 KES) per 1 USD.
 */
public record RateJson(long value_minor, int exponent, String base_currency, String quote_currency) {

    public static RateJson of(Rate rate) {
        Rate.ApiRate api = rate.toApiRate();
        return new RateJson(api.valueMinor(), api.exponent(), rate.baseCurrency(), rate.quoteCurrency());
    }
}
