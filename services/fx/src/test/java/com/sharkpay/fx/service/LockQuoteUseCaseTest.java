package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteExpiredException;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.domain.QuoteStateException;
import com.sharkpay.fx.events.CloudEvent;
import com.sharkpay.fx.events.FxEvents;
import com.sharkpay.fx.domain.Rate;
import com.sharkpay.fx.testsupport.FxTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockQuoteUseCaseTest {

    private final FxTestEnv env = new FxTestEnv();

    @Test
    void locksAQuotedQuoteInsideItsTtl() {
        Quote quote = env.newQuote("USD", "KES", 10000);
        LockQuoteUseCase.LockResult result = env.lockQuote.lock(quote.id());
        assertTrue(result.transitioned());
        assertEquals(QuoteState.LOCKED, result.quote().state());
        assertEquals(QuoteState.LOCKED, env.quotes.findById(quote.id()).orElseThrow().state());
    }

    @Test
    void publishesQuoteLockedEventMatchingTheContractEnvelope() {
        Quote quote = env.newQuote("USD", "KES", 10000);
        env.lockQuote.lock(quote.id());

        assertEquals(1, env.events.events().size());
        CloudEvent event = env.events.events().get(0);
        assertEquals(FxEvents.QUOTE_LOCKED, event.type());
        assertEquals(CloudEvent.SPECVERSION, event.specversion());
        assertEquals(CloudEvent.SOURCE, event.source());
        assertEquals(quote.id(), event.subject());
        assertTrue(event.id().matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
                "event id must be a UUID");
        assertEquals(env.clock.instant(), event.occurredAt());

        FxEvents.QuoteLockedData data = (FxEvents.QuoteLockedData) event.data();
        assertEquals(quote.id(), data.quote_id());
        assertEquals("USD", data.base_currency());
        assertEquals("KES", data.quote_currency());
        assertEquals(new Rate.ApiRate(127065, 1), new Rate.ApiRate(data.rate().value_minor(), data.rate().exponent()));
        assertEquals("USD", data.rate().base_currency());
        assertEquals("KES", data.rate().quote_currency());
        assertEquals(quote.expiresAt(), data.expires_at());
    }

    @Test
    void relockIsIdempotentAndPublishesNoSecondEvent() {
        Quote quote = env.newQuote("USD", "KES", 10000);
        assertTrue(env.lockQuote.lock(quote.id()).transitioned());
        LockQuoteUseCase.LockResult second = env.lockQuote.lock(quote.id());
        assertFalse(second.transitioned());
        assertEquals(QuoteState.LOCKED, second.quote().state());
        assertEquals(1, env.events.events().size());
    }

    @Test
    void lockingAnExpiredQuoteIsRejected() {
        Quote quote = env.createQuote.create(10000, "USD", "KES", 5).quote();
        env.clock.advance(Duration.ofSeconds(6));
        assertThrows(QuoteExpiredException.class, () -> env.lockQuote.lock(quote.id()));
        assertEquals(QuoteState.QUOTED, env.quotes.findById(quote.id()).orElseThrow().state());
        assertEquals(0, env.events.events().size());
    }

    @Test
    void lockingAnExecutedQuoteIsRejected() {
        Quote quote = env.newLockedQuote("USD", "KES", 10000);
        env.convert.convert("key-lock-1", quote.id(), "wallet:usr_1:USD", "wallet:usr_1:KES");
        assertThrows(QuoteStateException.class, () -> env.lockQuote.lock(quote.id()));
    }

    @Test
    void unknownQuoteIsRejected() {
        assertThrows(NoSuchElementException.class, () -> env.lockQuote.lock("fxq_missing"));
    }

    @Test
    void blankQuoteIdIsRejected() {
        assertThrows(FxDomainException.class, () -> env.lockQuote.lock(" "));
        assertThrows(FxDomainException.class, () -> env.lockQuote.lock(null));
    }
}
