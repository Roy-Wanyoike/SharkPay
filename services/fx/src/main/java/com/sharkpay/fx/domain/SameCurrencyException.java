package com.sharkpay.fx.domain;

/**
 * A quote was requested for a single currency — FX requires two distinct
 * currencies (HTTP 422 {@code same_currency}).
 */
public final class SameCurrencyException extends FxDomainException {

    public SameCurrencyException(String currency) {
        super("base and quote currency must differ, both are " + currency);
    }
}
