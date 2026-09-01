package com.sharkpay.fx.fakes;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.ports.QuoteRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory quote repository (fake for tests and local dev wiring). */
public final class InMemoryQuoteRepository implements QuoteRepository {

    private final Map<String, Quote> store = new ConcurrentHashMap<>();

    @Override
    public Quote save(Quote quote) {
        store.put(quote.id(), quote);
        return quote;
    }

    @Override
    public Optional<Quote> findById(String quoteId) {
        return Optional.ofNullable(store.get(quoteId));
    }

    @Override
    public List<Quote> findExpiredQuoted(Instant now) {
        return store.values().stream()
                .filter(quote -> quote.isExpiredAt(now))
                .sorted(Comparator.comparing(Quote::expiresAt).thenComparing(Quote::id))
                .toList();
    }
}
