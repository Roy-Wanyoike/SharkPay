package com.sharkpay.fx.storage;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.domain.Rate;
import com.sharkpay.fx.ports.StoredRequest;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the JPA port adapters without a database: Spring Data interfaces
 * are replaced by Mockito-backed in-memory doubles with the same semantics
 * as the Flyway schema (unique quote_id / conversion_id / ledger_txn_key /
 * idempotency_key). Verifies delegation + mapping + the expiry-sweep query
 * semantics + the unique-race swallow of the idempotency store — mirroring
 * the wallet service's JpaAdaptersTest.
 */
class JpaAdaptersTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final String QUOTE_ID = "fxq_0123456789abcdef0123456789abcdef";

    private Quote quoted(String id, Instant createdAt, Duration ttl) {
        return Quote.quoted(id, Money.of(10_000, "USD"), new Rate(25413, 200, "USD", "KES"),
                150, ttl, createdAt);
    }

    @Test
    void quoteAdapterSavesNewQuotesAndUpsertsExistingOnes() {
        QuoteJpaRepository jpa = Mockito.mock(QuoteJpaRepository.class);
        JpaQuoteRepository adapter = new JpaQuoteRepository(jpa);
        Quote quote = quoted(QUOTE_ID, T0, Duration.ofSeconds(30));

        when(jpa.findByQuoteId(QUOTE_ID)).thenReturn(Optional.empty());
        when(jpa.save(any(QuoteEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(adapter.save(quote)).usingRecursiveComparison().isEqualTo(quote);

        // an existing row is refreshed in place (lock transition)
        QuoteEntity existing = QuoteEntity.fromDomain(quote);
        when(jpa.findByQuoteId(QUOTE_ID)).thenReturn(Optional.of(existing));
        when(jpa.save(existing)).thenAnswer(inv -> inv.getArgument(0));
        Quote locked = QuoteEntity.fromDomain(quote).toDomain();
        locked.lock(T0.plusSeconds(1));
        assertThat(adapter.save(locked)).usingRecursiveComparison().isEqualTo(locked);
        verify(jpa, Mockito.times(2)).save(any(QuoteEntity.class));
    }

    @Test
    void quoteAdapterFindsByIdAndMaps() {
        QuoteJpaRepository jpa = Mockito.mock(QuoteJpaRepository.class);
        JpaQuoteRepository adapter = new JpaQuoteRepository(jpa);
        Quote quote = quoted(QUOTE_ID, T0, Duration.ofSeconds(30));

        when(jpa.findByQuoteId(QUOTE_ID)).thenReturn(Optional.of(QuoteEntity.fromDomain(quote)));
        when(jpa.findByQuoteId("fxq_missing")).thenReturn(Optional.empty());

        assertThat(adapter.findById(QUOTE_ID)).hasValueSatisfying(
                loaded -> assertThat(loaded).usingRecursiveComparison().isEqualTo(quote));
        assertThat(adapter.findById("fxq_missing")).isEmpty();
    }

    @Test
    void quoteAdapterSweepDelegatesWithQuotedStatusAndNow() {
        QuoteJpaRepository jpa = Mockito.mock(QuoteJpaRepository.class);
        JpaQuoteRepository adapter = new JpaQuoteRepository(jpa);
        Quote overdue = quoted(QUOTE_ID, T0, Duration.ofSeconds(30));
        Instant now = T0.plusSeconds(31);

        when(jpa.findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscQuoteIdAsc(
                QuoteState.QUOTED, now))
                .thenReturn(List.of(QuoteEntity.fromDomain(overdue)));

        assertThat(adapter.findExpiredQuoted(now)).containsExactly(overdue);

        verify(jpa).findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscQuoteIdAsc(
                QuoteState.QUOTED, now);
    }

    @Test
    void conversionAdapterSavesFindsAndListsOldestFirst() {
        ConversionJpaRepository jpa = Mockito.mock(ConversionJpaRepository.class);
        JpaConversionRepository adapter = new JpaConversionRepository(jpa);

        com.sharkpay.fx.domain.Conversion first = conversion("cnv_" + "1".repeat(26), T0);
        com.sharkpay.fx.domain.Conversion second = conversion("cnv_" + "2".repeat(26),
                T0.plusSeconds(5));

        when(jpa.findByConversionId(first.id())).thenReturn(Optional.empty());
        when(jpa.save(any(ConversionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(adapter.save(first)).isEqualTo(first);

        ConversionEntity existing = ConversionEntity.fromDomain(second);
        when(jpa.findByConversionId(second.id())).thenReturn(Optional.of(existing));
        when(jpa.save(existing)).thenAnswer(inv -> inv.getArgument(0));
        assertThat(adapter.save(second)).isEqualTo(second);

        when(jpa.findByConversionId(first.id())).thenReturn(Optional.of(ConversionEntity.fromDomain(first)));
        assertThat(adapter.findById(first.id())).contains(first);

        when(jpa.findAllByOrderByCreatedAtAscConversionIdAsc())
                .thenReturn(List.of(ConversionEntity.fromDomain(first),
                        ConversionEntity.fromDomain(second)));
        assertThat(adapter.findAll()).containsExactly(first, second);
    }

    @Test
    void conversionAdapterMergesInvolvedCurrenciesSortedAndDistinct() {
        ConversionJpaRepository jpa = Mockito.mock(ConversionJpaRepository.class);
        JpaConversionRepository adapter = new JpaConversionRepository(jpa);

        when(jpa.findDistinctSourceCurrencies()).thenReturn(List.of("USD", "EUR"));
        when(jpa.findDistinctTargetCurrencies()).thenReturn(List.of("KES", "USD"));

        assertThat(adapter.findInvolvedCurrencies()).containsExactly("EUR", "KES", "USD");
    }

    @Test
    void idempotencyAdapterMapsFindPutAndRemove() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        JpaIdempotencyStore adapter = new JpaIdempotencyStore(jpa);
        StoredRequest request = new StoredRequest("quote|src|dst", "cnv_" + "3".repeat(26));

        IdempotencyKeyEntity row = IdempotencyKeyEntity.of("key-1", request, T0);
        when(jpa.findByIdempotencyKey("key-1")).thenReturn(Optional.of(row));
        when(jpa.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());

        assertThat(adapter.find("key-1")).contains(request);
        assertThat(adapter.find("key-2")).isEmpty();

        adapter.put("key-2", request);
        verify(jpa).saveAndFlush(any(IdempotencyKeyEntity.class));

        adapter.remove("key-1");
        verify(jpa).deleteByIdempotencyKey("key-1");
    }

    @Test
    void idempotencyAdapterSwallowsTheUniqueKeyRace() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        JpaIdempotencyStore adapter = new JpaIdempotencyStore(jpa);
        StoredRequest request = new StoredRequest("quote|src|dst", "cnv_" + "4".repeat(26));

        Mockito.doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(jpa).saveAndFlush(any(IdempotencyKeyEntity.class));

        assertThatCode(() -> adapter.put("raced-key", request)).doesNotThrowAnyException();
    }

    @Test
    void idempotencyAdapterTreatsRemovingAnAbsentKeyAsANoOp() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        JpaIdempotencyStore adapter = new JpaIdempotencyStore(jpa);

        Mockito.doThrow(new EmptyResultDataAccessException(1))
                .when(jpa).deleteByIdempotencyKey("never-stored");

        assertThatCode(() -> adapter.remove("never-stored")).doesNotThrowAnyException();
    }

    private com.sharkpay.fx.domain.Conversion conversion(String id, Instant createdAt) {
        return new com.sharkpay.fx.domain.Conversion(id, QUOTE_ID, "wallet/src-USD",
                "wallet/dst-KES", Money.of(10_000, "USD"), Money.of(1_270_650, "KES"),
                new Rate(25413, 200, "USD", "KES"), "fx:" + id, UUID.randomUUID().toString(),
                com.sharkpay.fx.domain.ConversionState.EXECUTED, createdAt);
    }
}
