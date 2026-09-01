package com.sharkpay.fx.ports;

import com.sharkpay.fx.domain.Quote;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for quotes (owned by the FX service).
 *
 * <p>Production adapter: JPA-backed (storage package, wired at integration).
 * Production adapter: {@code storage.JpaQuoteRepository} (JPA, component-scanned).
 * Local tests use the in-tree fakes in {@code com.sharkpay.fx.fakes} (src/test).
 */
public interface QuoteRepository {

    /** Upserts a quote by its id. */
    Quote save(Quote quote);

    Optional<Quote> findById(String quoteId);

    /**
     * All QUOTED quotes whose TTL has elapsed ({@code expiresAt <= now}) —
     * input to the expiry sweep. LOCKED quotes are never returned.
     */
    List<Quote> findExpiredQuoted(Instant now);
}
