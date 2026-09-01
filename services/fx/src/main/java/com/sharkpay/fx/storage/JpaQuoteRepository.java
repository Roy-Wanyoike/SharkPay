package com.sharkpay.fx.storage;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.ports.QuoteRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA adapter for the quote repository port: delegation + entity mapping
 * (no business logic — the domain owns the rules). Upsert keyed on the
 * public {@code fxq_...} id; the expiry sweep query mirrors the domain's
 * "QUOTED only, expires_at ≤ now" semantics. Component-scanned production
 * adapter (mirrors the wallet service's storage package); local tests run
 * on the in-tree fake per ADR 003.
 */
@Repository
public final class JpaQuoteRepository implements QuoteRepository {

    private final QuoteJpaRepository jpa;

    public JpaQuoteRepository(QuoteJpaRepository jpa) {
        this.jpa = Objects.requireNonNull(jpa, "quoteJpaRepository is required");
    }

    @Override
    public Quote save(Quote quote) {
        return jpa.findByQuoteId(quote.id())
                .map(entity -> {
                    entity.applyDomain(quote);
                    return jpa.save(entity).toDomain();
                })
                .orElseGet(() -> jpa.save(QuoteEntity.fromDomain(quote)).toDomain());
    }

    @Override
    public Optional<Quote> findById(String quoteId) {
        return jpa.findByQuoteId(quoteId).map(QuoteEntity::toDomain);
    }

    @Override
    public List<Quote> findExpiredQuoted(Instant now) {
        return jpa.findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscQuoteIdAsc(QuoteState.QUOTED, now)
                .stream()
                .map(QuoteEntity::toDomain)
                .toList();
    }
}
