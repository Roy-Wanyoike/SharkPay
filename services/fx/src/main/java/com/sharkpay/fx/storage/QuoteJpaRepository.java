package com.sharkpay.fx.storage;

import com.sharkpay.fx.domain.QuoteState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link QuoteEntity}. Query derivation mirrors
 * the domain port: {@code findExpiredQuoted} &#8592; QUOTED status with
 * expires_at &#8804; now (the sweep partial index), deterministic order
 * (expires_at, then public id) like the in-tree fake.
 */
public interface QuoteJpaRepository extends JpaRepository<QuoteEntity, UUID> {

    Optional<QuoteEntity> findByQuoteId(String quoteId);

    List<QuoteEntity> findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscQuoteIdAsc(
            QuoteState status, Instant expiresAt);
}
