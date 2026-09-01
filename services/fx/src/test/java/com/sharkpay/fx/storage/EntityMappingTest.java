package com.sharkpay.fx.storage;

import com.sharkpay.fx.domain.Conversion;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.domain.Rate;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain &#8596; entity mapping round trips for the JPA layer (mirrors the
 * wallet service's EntityMappingTest): every persisted quote/conversion must
 * rehydrate to an equal domain object, through ALL lifecycle states, and
 * stablecoin pairs (4-letter codes) must survive the currency columns.
 */
class EntityMappingTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final String QUOTE_ID = "fxq_0123456789abcdef0123456789abcdef";

    @Test
    void quotedQuoteRoundTripsPreservingAllFields() {
        Quote quoted = Quote.quoted(QUOTE_ID, Money.of(10_000, "USD"),
                new Rate(25413, 200, "USD", "KES"), 150, Duration.ofSeconds(30), T0);

        Quote loaded = QuoteEntity.fromDomain(quoted).toDomain();

        // full-field comparison (Quote.equals is id-based): every persisted
        // value must survive the round trip
        assertThat(loaded).usingRecursiveComparison().isEqualTo(quoted);
        assertThat(loaded.state()).isEqualTo(QuoteState.QUOTED);
        assertThat(loaded.targetAmount()).isEqualTo(Money.of(1_270_650, "KES"));
    }

    @Test
    void quoteRoundTripsThroughEveryLifecycleState() {
        Quote quote = Quote.quoted(QUOTE_ID, Money.of(10_000, "USD"),
                new Rate(25413, 200, "USD", "KES"), 150, Duration.ofSeconds(30), T0);

        quote.lock(T0.plusSeconds(1));
        assertThat(QuoteEntity.fromDomain(quote).toDomain())
                .usingRecursiveComparison().isEqualTo(quote);

        quote.execute();
        assertThat(QuoteEntity.fromDomain(quote).toDomain())
                .usingRecursiveComparison().isEqualTo(quote);

        Quote expired = Quote.quoted(QUOTE_ID, Money.of(10_000, "USD"),
                new Rate(25413, 200, "USD", "KES"), 150, Duration.ofSeconds(30), T0);
        expired.expire();
        assertThat(QuoteEntity.fromDomain(expired).toDomain())
                .usingRecursiveComparison().isEqualTo(expired);
    }

    @Test
    void applyDomainRefreshesTheLifecycleFieldsInPlace() {
        QuoteEntity entity = QuoteEntity.fromDomain(Quote.quoted(QUOTE_ID,
                Money.of(10_000, "USD"), new Rate(25413, 200, "USD", "KES"), 150,
                Duration.ofSeconds(30), T0));
        Quote loaded = entity.toDomain();

        loaded.lock(T0.plusSeconds(2));
        loaded.execute();
        entity.applyDomain(loaded);

        assertThat(entity.status).isEqualTo(QuoteState.EXECUTED);
        assertThat(entity.toDomain()).usingRecursiveComparison().isEqualTo(loaded);
    }

    @Test
    void stablecoinPairSurvivesTheFourLetterCurrencyColumns() {
        Quote quote = Quote.quoted("fxq_" + "7".repeat(26), Money.of(2_000_000, "USD"),
                new Rate(9850, 1, "USD", "USDC"), 150, Duration.ofSeconds(30), T0);

        Quote loaded = QuoteEntity.fromDomain(quote).toDomain();

        assertThat(loaded).usingRecursiveComparison().isEqualTo(quote);
        assertThat(loaded.targetAmount().currency()).isEqualTo("USDC");
        assertThat(loaded.targetAmount().exponent()).isEqualTo(6);
    }

    @Test
    void conversionRoundTripsPreservingAllFields() {
        String entryId = UUID.randomUUID().toString();
        Conversion conversion = new Conversion("cnv_" + "9".repeat(26), QUOTE_ID,
                "wallet/src-USD", "wallet/dst-KES", Money.of(10_000, "USD"),
                Money.of(1_270_650, "KES"), new Rate(25413, 200, "USD", "KES"),
                "fx:cnv_" + "9".repeat(26), entryId,
                com.sharkpay.fx.domain.ConversionState.EXECUTED, T0);

        Conversion loaded = ConversionEntity.fromDomain(conversion).toDomain();

        assertThat(loaded).isEqualTo(conversion);
        assertThat(loaded.ledgerEntryId()).isEqualTo(entryId);
    }

    @Test
    void conversionEntityApplyDomainRefreshesInPlace() {
        Conversion conversion = new Conversion("cnv_" + "9".repeat(26), QUOTE_ID,
                "wallet/src", "wallet/dst", Money.of(100, "EUR"), Money.of(108, "USD"),
                new Rate(27, 25, "EUR", "USD"), "fx:cnv_x", UUID.randomUUID().toString(),
                com.sharkpay.fx.domain.ConversionState.EXECUTED, T0);
        ConversionEntity entity = ConversionEntity.fromDomain(conversion);

        entity.applyDomain(conversion);

        assertThat(entity.toDomain()).isEqualTo(conversion);
        assertThat(entity.quoteId).isEqualTo(QUOTE_ID);
    }

    @Test
    void idempotencyKeyEntityMapsTheStoredRequest() {
        com.sharkpay.fx.ports.StoredRequest request =
                new com.sharkpay.fx.ports.StoredRequest("quote|src|dst", "cnv_" + "1".repeat(26));

        IdempotencyKeyEntity entity = IdempotencyKeyEntity.of("idem-key-1", request, T0);

        assertThat(entity.idempotencyKey).isEqualTo("idem-key-1");
        assertThat(entity.createdAt).isEqualTo(T0);
        assertThat(entity.toStoredRequest()).isEqualTo(request);
        assertThat(entity.id).isNotNull();
    }
}
