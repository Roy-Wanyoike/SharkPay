package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.fx.domain.MarkupPolicy;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.domain.Rate;
import com.sharkpay.fx.domain.SameCurrencyException;
import com.sharkpay.fx.domain.UnsupportedCurrencyPairException;
import com.sharkpay.fx.ports.RateProvider;
import com.sharkpay.fx.testsupport.FxTestEnv;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateQuoteUseCaseTest {

    private final FxTestEnv env = new FxTestEnv();

    @Test
    void createsAQuotedQuoteWithExactMarkupMath() {
        CreateQuoteUseCase.CreateQuoteResult result = env.createQuote.create(10000, "USD", "KES", null);
        Quote quote = result.quote();
        assertTrue(quote.id().matches("^fxq_[0-9A-Za-z]{20,}$"), "id must match the contract pattern");
        assertEquals(QuoteState.QUOTED, quote.state());
        // raw 129/1 with 150 bps markup → 25413/200 exactly
        assertEquals(new Rate(25413, 200, "USD", "KES"), quote.rate());
        assertEquals(150, quote.markupBps());
        // indicative target: 10000 × 25413/200 = 1270650 (dust 0)
        assertEquals(Money.of(1270650, "KES"), quote.targetAmount());
        // default TTL 30s
        assertEquals(env.clock.instant().plusSeconds(30), quote.expiresAt());
        // persisted
        assertSame(quote, env.quotes.findById(quote.id()).orElseThrow());
    }

    @Test
    void opsSpreadBreakdownSplitsTheRawGrossExactly() {
        CreateQuoteUseCase.CreateQuoteResult result = env.createQuote.create(10000, "USD", "KES", null);
        // gross at the raw rate: 10000 × 129/1 = 1290000 KES-minor
        assertEquals(Money.of(1290000, "KES"), result.spread().grossTarget());
        // exact 9850:150 split — no remainder to distribute
        assertEquals(Money.of(1270650, "KES"), result.spread().toCustomer());
        assertEquals(Money.of(19350, "KES"), result.spread().markup());
        assertEquals(result.spread().grossTarget(),
                result.spread().toCustomer().add(result.spread().markup()));
    }

    @Test
    void honorsTheRequestedTtl() {
        Quote quote = env.createQuote.create(5000, "USD", "KES", 5).quote();
        assertEquals(env.clock.instant().plus(Duration.ofSeconds(5)), quote.expiresAt());
    }

    @Test
    void crossExponentPairsQuoteCorrectly() {
        // USD (exponent 2) → USDC (exponent 6): raw 10000/1, markup 150 → 9850/1
        Quote quote = env.createQuote.create(2_000_000, "USD", "USDC", null).quote();
        assertEquals(new Rate(9850, 1, "USD", "USDC"), quote.rate());
        assertEquals(Money.of(19_700_000_000L, "USDC"), quote.targetAmount());
        // API rate: 985000 USDC-minor per 1 USD = 0.985 USDC per USD
        assertEquals(new Rate.ApiRate(985000, 0), quote.rate().toApiRate());
    }

    @Test
    void dustTruncationIsAppliedToTheIndicativeTarget() {
        // 100 USD-minor at 25413/200 → exact 12706.5 → target 12706, dust 100/200
        Quote quote = env.createQuote.create(100, "USD", "KES", null).quote();
        assertEquals(new Rate(25413, 200, "USD", "KES"), quote.rate());
        assertEquals(Money.of(12706, "KES"), quote.targetAmount());
    }

    @Test
    void rejectsSameCurrency() {
        assertThrows(SameCurrencyException.class, () -> env.createQuote.create(100, "USD", "USD", null));
        assertThrows(SameCurrencyException.class, () -> env.createQuote.create(100, "KES", " KES ", null));
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThrows(InvalidAmountException.class, () -> env.createQuote.create(0, "USD", "KES", null));
        assertThrows(InvalidAmountException.class, () -> env.createQuote.create(-5, "USD", "KES", null));
    }

    @Test
    void rejectsUnsupportedPair() {
        // known currencies, but no rate source serves the pair
        assertThrows(UnsupportedCurrencyPairException.class, () -> env.createQuote.create(100, "GBP", "KES", null));
        assertThrows(UnsupportedCurrencyPairException.class, () -> env.createQuote.create(100, "USD", "USDT", null));
    }

    @Test
    void rejectsUnknownCurrencyAsAnUnquotablePair() {
        // Unknown currency codes (e.g. XXX/XYZ outside the supported set) are a
        // pair-level business rejection — 422 unsupported_currency_pair per
        // contracts/openapi/v1/fx.yaml — not a 400 validation error: the domain
        // translates the money library's UnknownCurrencyException uniformly.
        assertThrows(UnsupportedCurrencyPairException.class, () -> env.createQuote.create(100, "XYZ", "KES", null));
        assertThrows(UnsupportedCurrencyPairException.class, () -> env.createQuote.create(100, "XXX", "KES", null));
        assertThrows(UnsupportedCurrencyPairException.class, () -> env.createQuote.create(100, "USD", "XXX", null));
    }

    @Test
    void quotesCaseInsensitiveCurrencyCodes() {
        // codes are canonicalised (trimmed + upper-cased) before the rate lookup
        Quote quote = env.createQuote.create(10000, " usd ", "kes", null).quote();
        assertEquals(Money.of(10000, "USD"), quote.sourceAmount());
        assertEquals("KES", quote.quoteCurrency());
        assertEquals(QuoteState.QUOTED, quote.state());
    }

    @Test
    void rejectsBlankCurrencies() {
        assertThrows(FxDomainException.class, () -> env.createQuote.create(100, " ", "KES", null));
        assertThrows(FxDomainException.class, () -> env.createQuote.create(100, "USD", null, null));
    }

    @Test
    void sameCurrencyIsDetectedCaseInsensitively() {
        assertThrows(SameCurrencyException.class, () -> env.createQuote.create(100, "usd", "USD", null));
    }

    @Test
    void rejectsANonPositiveDefaultTtlAtConstruction() {
        RateProvider rates = env.rates;
        MarkupPolicy markup = env.markup;
        assertThrows(FxDomainException.class,
                () -> new CreateQuoteUseCase(rates, markup, env.quotes, env.clock, Duration.ZERO));
        assertThrows(FxDomainException.class,
                () -> new CreateQuoteUseCase(rates, markup, env.quotes, env.clock, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class,
                () -> new CreateQuoteUseCase(rates, markup, env.quotes, env.clock, null));
    }
}
