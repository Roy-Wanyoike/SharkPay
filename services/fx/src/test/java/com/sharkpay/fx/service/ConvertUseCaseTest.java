package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.Conversion;
import com.sharkpay.fx.domain.Direction;
import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.fx.domain.IdempotencyConflictException;
import com.sharkpay.fx.domain.Leg;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteExpiredException;
import com.sharkpay.fx.domain.QuoteStateException;
import com.sharkpay.fx.events.FxEvents;
import com.sharkpay.fx.testsupport.FxTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Convert is the money-moving use case of the FX service — the tests assert
 * the money-safety gates of ADR 003 G2: idempotency (same key = same result,
 * no double ledger effect), no double-spend of a quote, deterministic leg
 * amounts, and reservation release on failure.
 */
class ConvertUseCaseTest {

    private final FxTestEnv env = new FxTestEnv();

    @Test
    void executesALockedQuoteAsAFourLegLedgerPosting() {
        Quote quote = env.newLockedQuote("USD", "KES", 10000);

        ConvertUseCase.Result result = env.convert.convert(
                "key-1", quote.id(), "wallet/src-USD", "wallet/dst-KES");

        assertFalse(result.replay());
        Conversion conversion = result.conversion();
        assertEquals("cnv_", conversion.id().substring(0, 4));
        assertEquals(quote.id(), conversion.quoteId());
        assertEquals("wallet/src-USD", conversion.sourceWalletRef());
        assertEquals("wallet/dst-KES", conversion.destinationWalletRef());

        // quote consumed exactly once
        assertEquals(com.sharkpay.fx.domain.QuoteState.EXECUTED,
                env.quotes.findById(quote.id()).orElseThrow().state());

        // 4-leg posting posted to the ledger under the conversion's txn key
        String txnKey = "fx:" + conversion.id();
        List<Leg> legs = env.ledger.legsPostedFor(txnKey).orElseThrow();
        assertEquals(4, legs.size());
        long debitMinor = legs.stream()
                .filter(leg -> leg.direction() == Direction.DEBIT)
                .mapToLong(Leg::amountMinor).sum();
        long creditMinor = legs.stream()
                .filter(leg -> leg.direction() == Direction.CREDIT)
                .mapToLong(Leg::amountMinor).sum();
        assertEquals(debitMinor, creditMinor, "total debited minor units must equal total credited minor units");
        assertTrue(legs.stream().anyMatch(leg -> leg.accountRef().equals("wallet/src-USD")));
        assertTrue(legs.stream().anyMatch(leg -> leg.accountRef().equals("wallet/dst-KES")));
        assertEquals(conversion.ledgerEntryId(), env.ledger.entryIdFor(txnKey).orElseThrow());

        // conversion persisted + event published exactly once
        assertEquals(conversion, env.conversions.findById(conversion.id()).orElseThrow());
        assertEquals(1, env.events.eventsOfType(FxEvents.CONVERSION_EXECUTED).size());
    }

    @Test
    void replaysTheSameKeyWithNoDoubleEffect() {
        Quote quote = env.newLockedQuote("USD", "KES", 10000);

        ConvertUseCase.Result first = env.convert.convert("key-2", quote.id(), "src", "dst");
        long postAttemptsAfterFirst = env.ledger.postAttempts();

        ConvertUseCase.Result replay = env.convert.convert("key-2", quote.id(), "src", "dst");

        assertTrue(replay.replay());
        assertEquals(first.conversion(), replay.conversion());
        assertEquals(postAttemptsAfterFirst, env.ledger.postAttempts(), "replay must not re-post to the ledger");
        assertEquals(1, env.conversions.findAll().size());
        assertEquals(1, env.events.eventsOfType(FxEvents.CONVERSION_EXECUTED).size());
    }

    @Test
    void sameKeyDifferentBodyIsAConflict() {
        Quote firstQuote = env.newLockedQuote("USD", "KES", 10000);
        env.convert.convert("key-3", firstQuote.id(), "src", "dst");

        Quote secondQuote = env.newLockedQuote("EUR", "KES", 10000);
        assertThrows(IdempotencyConflictException.class,
                () -> env.convert.convert("key-3", secondQuote.id(), "src", "dst"));
    }

    @Test
    void unknownQuoteIsRejected() {
        assertThrows(NoSuchElementException.class,
                () -> env.convert.convert("key-4", "fxq_unknown", "src", "dst"));
    }

    @Test
    void quotedQuoteMustBeLockedBeforeConversion() {
        Quote quote = env.newQuote("USD", "KES", 10000);
        QuoteStateException e = assertThrows(QuoteStateException.class,
                () -> env.convert.convert("key-5", quote.id(), "src", "dst"));
        assertTrue(e.getMessage().contains("QUOTED"));
    }

    @Test
    void expiredQuotedQuoteIsRejectedEvenIfSomehowUnlocked() {
        Quote quote = env.newQuote("USD", "KES", 10000); // TTL 30s in the default env
        env.clock.advance(Duration.ofSeconds(31));
        assertThrows(QuoteExpiredException.class,
                () -> env.convert.convert("key-6", quote.id(), "src", "dst"));
    }

    @Test
    void lockedQuotesNeverAutoExpire() {
        Quote quote = env.newLockedQuote("USD", "KES", 10000);
        env.clock.advance(Duration.ofHours(8)); // far beyond the original TTL

        ConvertUseCase.Result result = env.convert.convert("key-7", quote.id(), "src", "dst");
        assertFalse(result.replay());
        assertEquals(1, env.conversions.findAll().size());
    }

    @Test
    void releasesTheReservationWhenThePostingFails() {
        Quote quote = env.newLockedQuote("USD", "KES", 10000);
        env.ledger.failNextPosting();

        assertThrows(FxDomainException.class,
                () -> env.convert.convert("key-8", quote.id(), "src", "dst"));

        // the failed key is released: retrying the SAME key works and is NOT a replay
        ConvertUseCase.Result retry = env.convert.convert("key-8", quote.id(), "src", "dst");
        assertFalse(retry.replay());
        assertEquals(1, env.conversions.findAll().size());
    }

    @Test
    void blankArgumentsAreRejected() {
        Quote quote = env.newLockedQuote("USD", "KES", 10000);
        assertThrows(FxDomainException.class, () -> env.convert.convert("", quote.id(), "src", "dst"));
        assertThrows(FxDomainException.class, () -> env.convert.convert("key", " ", "src", "dst"));
        assertThrows(FxDomainException.class, () -> env.convert.convert("key", quote.id(), null, "dst"));
    }

    @Test
    void distinctKeysProduceDistinctConversions() {
        Quote firstQuote = env.newLockedQuote("USD", "KES", 10000);
        Quote secondQuote = env.newLockedQuote("EUR", "KES", 10000);

        ConvertUseCase.Result a = env.convert.convert("key-a", firstQuote.id(), "src", "dst");
        ConvertUseCase.Result b = env.convert.convert("key-b", secondQuote.id(), "src", "dst");

        assertNotEquals(a.conversion().id(), b.conversion().id());
        assertEquals(2, env.conversions.findAll().size());
        assertEquals(2, env.events.eventsOfType(FxEvents.CONVERSION_EXECUTED).size());
    }

    @Test
    void rejectsAPersistedQuoteWhoseTargetDisagreesWithItsRate() {
        // money-safety invariant: even a corrupted row (target amount
        // inconsistent with rate × source) can never post wrong money — the
        // deterministic recomputation gate rejects it before the ledger
        Quote legit = env.newLockedQuote("USD", "KES", 10000);
        Quote corrupted = Quote.rehydrate(legit.id(), "USD", "KES", legit.sourceAmount(),
                com.sharkpay.money.Money.of(1, "KES"), legit.rate(), legit.markupBps(),
                legit.expiresAt(), legit.createdAt(), com.sharkpay.fx.domain.QuoteState.LOCKED);
        env.quotes.save(corrupted);

        FxDomainException e = assertThrows(FxDomainException.class,
                () -> env.convert.convert("key-corrupt", corrupted.id(), "src", "dst"));
        assertTrue(e.getMessage().contains("inconsistent"));
        assertEquals(0, env.conversions.findAll().size(), "no conversion may be persisted");
        assertEquals(0, env.ledger.postAttempts(), "the ledger must stay untouched");
    }
}
