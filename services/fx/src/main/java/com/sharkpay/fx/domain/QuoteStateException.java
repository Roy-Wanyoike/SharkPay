package com.sharkpay.fx.domain;

/**
 * An operation is not legal for the quote's current state (HTTP 409
 * {@code state_conflict}). Example: converting a quote that is not LOCKED.
 */
public final class QuoteStateException extends FxDomainException {

    public QuoteStateException(String quoteId, QuoteState state, String operation) {
        super("quote " + quoteId + " is " + state + " and cannot be " + operation + "ed");
    }
}
