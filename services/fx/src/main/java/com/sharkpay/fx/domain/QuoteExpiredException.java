package com.sharkpay.fx.domain;

import java.time.Instant;

/**
 * A QUOTED quote's TTL elapsed before it could be locked or converted
 * (HTTP 409 {@code state_conflict} per contracts/openapi/v1/fx.yaml).
 */
public final class QuoteExpiredException extends FxDomainException {

    public QuoteExpiredException(String quoteId, Instant expiresAt, Instant now) {
        super("quote " + quoteId + " expired at " + expiresAt + " (now=" + now + ")");
    }
}
