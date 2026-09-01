package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.ports.QuoteRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Expiry sweep: transitions QUOTED quotes whose TTL has elapsed to EXPIRED.
 * LOCKED quotes are never touched — their rate is guaranteed (expiry of a
 * locked quote would be a p1 incident, docs/STATE-MACHINES.md &#167;4).
 */
public final class ExpireQuotesUseCase {

    private final QuoteRepository quotes;
    private final Clock clock;

    public ExpireQuotesUseCase(QuoteRepository quotes, Clock clock) {
        this.quotes = Objects.requireNonNull(quotes, "quoteRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** @return the number of quotes swept QUOTED&#8594;EXPIRED */
    public int expireOverdue() {
        java.time.Instant now = clock.instant();
        List<Quote> overdue = quotes.findExpiredQuoted(now);
        for (Quote quote : overdue) {
            quote.expire();
            quotes.save(quote);
        }
        return overdue.size();
    }
}
