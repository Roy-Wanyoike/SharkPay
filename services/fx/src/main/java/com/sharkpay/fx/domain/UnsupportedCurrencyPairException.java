package com.sharkpay.fx.domain;

/**
 * The configured rate source does not serve the requested pair (HTTP 422
 * {@code unsupported_currency_pair}).
 */
public final class UnsupportedCurrencyPairException extends FxDomainException {

    public UnsupportedCurrencyPairException(String base, String quote) {
        super("no rate source serves the currency pair " + base + "/" + quote);
    }
}
