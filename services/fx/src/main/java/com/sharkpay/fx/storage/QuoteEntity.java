package com.sharkpay.fx.storage;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.domain.Rate;
import com.sharkpay.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of the {@code quotes} table (V1__fx_init.sql). Field-access
 * mapping with public fields; domain translation lives in
 * {@link #fromDomain(Quote)}, {@link #toDomain()} and {@link #applyDomain(Quote)}
 * (mirrors the wallet service's storage package). Currency columns are
 * length 4 — USDC/USDT are four letters (V1 currency set, PRD §7 D2).
 */
@Entity
@Table(name = "quotes")
public class QuoteEntity {

    /** Internal surrogate key (random UUID at insert). */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    public UUID id;

    /** Public quote id, {@code fxq_...} (contract pattern). */
    @Column(name = "quote_id", nullable = false, unique = true, length = 40)
    public String quoteId;

    @Column(name = "base_currency", nullable = false, length = 4)
    public String baseCurrency;

    @Column(name = "quote_currency", nullable = false, length = 4)
    public String quoteCurrency;

    @Column(name = "source_amount_minor", nullable = false)
    public long sourceAmountMinor;

    @Column(name = "source_exponent", nullable = false)
    public int sourceExponent;

    @Column(name = "target_amount_minor", nullable = false)
    public long targetAmountMinor;

    @Column(name = "target_exponent", nullable = false)
    public int targetExponent;

    /**
     * Rate numerator: quote-currency minor units per base minor unit
     * (exact rational).
     */
    @Column(name = "rate_num", nullable = false)
    public long rateNum;

    /** Rate denominator. */
    @Column(name = "rate_den", nullable = false)
    public long rateDen;

    @Column(name = "markup_bps", nullable = false)
    public int markupBps;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public QuoteState status;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /** New entity for a domain quote (fresh surrogate id). */
    public static QuoteEntity fromDomain(Quote quote) {
        QuoteEntity entity = new QuoteEntity();
        entity.id = UUID.randomUUID();
        entity.createdAt = quote.createdAt();
        entity.applyDomain(quote);
        return entity;
    }

    /** Maps to the domain quote (state stored as the enum name). */
    public Quote toDomain() {
        return Quote.rehydrate(quoteId, baseCurrency, quoteCurrency,
                Money.of(sourceAmountMinor, baseCurrency), Money.of(targetAmountMinor, quoteCurrency),
                new Rate(rateNum, rateDen, baseCurrency, quoteCurrency), markupBps, expiresAt, createdAt, status);
    }

    /** Refreshes every business field from the (possibly mutated) domain quote. */
    public void applyDomain(Quote quote) {
        Objects.requireNonNull(quote, "quote is required");
        this.quoteId = quote.id();
        this.baseCurrency = quote.baseCurrency();
        this.quoteCurrency = quote.quoteCurrency();
        this.sourceAmountMinor = quote.sourceAmount().amountMinor();
        this.sourceExponent = quote.sourceAmount().exponent();
        this.targetAmountMinor = quote.targetAmount().amountMinor();
        this.targetExponent = quote.targetAmount().exponent();
        this.rateNum = quote.rate().numerator();
        this.rateDen = quote.rate().denominator();
        this.markupBps = (int) quote.markupBps();
        this.status = quote.state();
        this.expiresAt = quote.expiresAt();
        this.updatedAt = Instant.now();
    }
}
