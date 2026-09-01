package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.fx.domain.MarkupPolicy;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.Rate;
import com.sharkpay.fx.domain.SameCurrencyException;
import com.sharkpay.fx.domain.UnsupportedCurrencyPairException;
import com.sharkpay.fx.ports.QuoteRepository;
import com.sharkpay.fx.ports.RateProvider;
import com.sharkpay.money.Currencies;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.Money;
import com.sharkpay.money.UnknownCurrencyException;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * GetQuote use-case: create a TTL'd indicative quote for a currency pair.
 *
 * <p>Pipeline: validate pair/amount &#8594; fetch the raw rate from the
 * {@link RateProvider} &#8594; apply the {@link MarkupPolicy} (exact integer
 * math) &#8594; derive the indicative target amount by rate conversion
 * (truncate + dust, README &#167;Rate semantics) &#8594; persist with
 * {@code state=QUOTED}, {@code expiresAt = now + ttl}.
 */
public final class CreateQuoteUseCase {

    private final RateProvider rateProvider;
    private final MarkupPolicy markupPolicy;
    private final QuoteRepository quotes;
    private final Clock clock;
    private final Duration defaultTtl;

    public CreateQuoteUseCase(RateProvider rateProvider, MarkupPolicy markupPolicy, QuoteRepository quotes,
                              Clock clock, Duration defaultTtl) {
        this.rateProvider = Objects.requireNonNull(rateProvider, "rateProvider is required");
        this.markupPolicy = Objects.requireNonNull(markupPolicy, "markupPolicy is required");
        this.quotes = Objects.requireNonNull(quotes, "quoteRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.defaultTtl = Objects.requireNonNull(defaultTtl, "defaultTtl is required");
        if (defaultTtl.isZero() || defaultTtl.isNegative()) {
            throw new FxDomainException("default quote TTL must be positive: " + defaultTtl);
        }
    }

    /**
     * @param amountMinor  source amount in base-currency minor units (&gt; 0)
     * @param ttlSeconds   optional TTL override (5..3600 enforced at the API
     *                     edge); null selects the service default
     * @return the created QUOTED quote plus the ops fee/markup breakdown
     */
    public CreateQuoteResult create(long amountMinor, String baseCurrency, String quoteCurrency, Integer ttlSeconds) {
        if (baseCurrency == null || quoteCurrency == null || baseCurrency.isBlank() || quoteCurrency.isBlank()) {
            throw new FxDomainException("base and quote currencies are required");
        }
        String base = baseCurrency.trim();
        String quote = quoteCurrency.trim();
        if (base.equalsIgnoreCase(quote)) {
            throw new SameCurrencyException(base);
        }
        if (amountMinor <= 0) {
            throw new InvalidAmountException("amount_minor must be a positive integer: " + amountMinor);
        }
        // Pair-level business rejection FIRST (contracts/openapi/v1/fx.yaml:
        // 422 unsupported_currency_pair): a pair is unquotable when a currency
        // is outside the supported set or no rate source serves it — the domain
        // surfaces both uniformly as UnsupportedCurrencyPairException so the
        // API maps them to 422, never to a 400 validation error.
        String baseCode;
        String quoteCode;
        try {
            baseCode = Currencies.normalize(base);
            quoteCode = Currencies.normalize(quote);
        } catch (UnknownCurrencyException unknownCurrency) {
            throw new UnsupportedCurrencyPairException(base, quote);
        }
        Duration ttl = ttlSeconds == null ? defaultTtl : Duration.ofSeconds(ttlSeconds);
        Money source = Money.of(amountMinor, baseCode);
        Rate raw = rateProvider.rawRate(baseCode, quoteCode);
        Rate quotedRate = markupPolicy.applyTo(raw);
        Quote quoteObject = Quote.quoted(Ids.newQuoteId(), source, quotedRate, markupPolicy.markupBps(), ttl,
                clock.instant());
        quotes.save(quoteObject);
        // Ops-only breakdown: the gross indicative target at the RAW rate,
        // split customer/platform via Money.allocate (never loses a minor unit).
        Money gross = raw.convert(source).target();
        Money[] split = markupPolicy.split(gross);
        return new CreateQuoteResult(quoteObject, new SpreadBreakdown(gross, split[0], split[1]));
    }

    /** The created quote plus the indicative fee/markup breakdown. */
    public record CreateQuoteResult(Quote quote, SpreadBreakdown spread) {
    }

    /**
     * Fee/markup split of the gross indicative target amount (valued at the
     * raw rate): {@code toCustomer + markup == grossTarget} exactly.
     */
    public record SpreadBreakdown(Money grossTarget, Money toCustomer, Money markup) {
    }
}
