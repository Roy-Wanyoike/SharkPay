package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.events.FxEvents;
import com.sharkpay.fx.ports.EventPublisher;
import com.sharkpay.fx.ports.QuoteRepository;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * LockQuote use-case: lock a QUOTED quote inside its TTL so its rate is
 * guaranteed. Locking is idempotent per quote (a second lock on a LOCKED
 * quote is a no-op); locking after expiry is rejected with a domain error.
 *
 * <p>On the QUOTED&#8594;LOCKED transition the
 * {@code fx.quote.locked.v1} event is published
 * (contracts/events/fx.v1.json).
 */
public final class LockQuoteUseCase {

    private final QuoteRepository quotes;
    private final EventPublisher events;
    private final Clock clock;

    public LockQuoteUseCase(QuoteRepository quotes, EventPublisher events, Clock clock) {
        this.quotes = Objects.requireNonNull(quotes, "quoteRepository is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public LockResult lock(String quoteId) {
        if (quoteId == null || quoteId.isBlank()) {
            throw new FxDomainException("quote id is required");
        }
        String id = quoteId.trim();
        Quote quote = quotes.findById(id)
                .orElseThrow(() -> new NoSuchElementException("quote " + id + " not found"));
        boolean transitioned = quote.lock(clock.instant());
        if (transitioned) {
            quotes.save(quote);
            events.publish(FxEvents.quoteLocked(quote, clock.instant()));
        }
        return new LockResult(quote, transitioned);
    }

    /**
     * @param quote        the quote after the lock attempt
     * @param transitioned true iff this call performed the QUOTED&#8594;LOCKED
     *                     transition (false = idempotent re-lock)
     */
    public record LockResult(Quote quote, boolean transitioned) {
    }
}
