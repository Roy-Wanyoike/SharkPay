package com.sharkpay.fx.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final Money SOURCE = Money.of(10000, "USD");
    private static final Rate RATE = new Rate(25413, 200, "USD", "KES");

    private Quote quote(Duration ttl) {
        return Quote.quoted("fxq_" + "0".repeat(26), SOURCE, RATE, 150, ttl, NOW);
    }

    @Test
    void derivesTargetAmountAndExpiryFromInputs() {
        Quote quote = quote(Duration.ofSeconds(30));
        assertEquals(QuoteState.QUOTED, quote.state());
        assertEquals(Money.of(1270650, "KES"), quote.targetAmount()); // 10000 × 25413/200, dust 0
        assertEquals(NOW.plusSeconds(30), quote.expiresAt());
        assertEquals(NOW, quote.createdAt());
        assertEquals(150, quote.markupBps());
        assertEquals(RATE, quote.rate());
        assertTrue(quote.toString().contains("fxq_"));
    }

    @Test
    void targetAmountTruncatesDust() {
        Quote quote = Quote.quoted("fxq_x", Money.of(100, "USD"), new Rate(1, 3, "USD", "KES"), 0,
                Duration.ofSeconds(30), NOW);
        assertEquals(Money.of(33, "KES"), quote.targetAmount());
    }

    @Test
    void locksWithinTtlAndIsIdempotent() {
        Quote quote = quote(Duration.ofSeconds(30));
        assertTrue(quote.lock(NOW.plusSeconds(29)), "first lock must transition");
        assertEquals(QuoteState.LOCKED, quote.state());
        assertFalse(quote.lock(NOW.plusSeconds(30)), "second lock is a no-op");
        assertEquals(QuoteState.LOCKED, quote.state());
    }

    @Test
    void lockAtOrAfterExpiryIsRejected() {
        Quote quote = quote(Duration.ofSeconds(30));
        assertThrows(QuoteExpiredException.class, () -> quote.lock(NOW.plusSeconds(30)));
        assertThrows(QuoteExpiredException.class, () -> quote.lock(NOW.plusSeconds(31)));
        assertEquals(QuoteState.QUOTED, quote.state());
    }

    @Test
    void expiryIsDetectedOnlyForQuotedQuotes() {
        Quote quote = quote(Duration.ofSeconds(30));
        assertFalse(quote.isExpiredAt(NOW.plusSeconds(29)));
        assertTrue(quote.isExpiredAt(NOW.plusSeconds(30)));
        quote.lock(NOW.plusSeconds(1));
        assertFalse(quote.isExpiredAt(NOW.plusSeconds(9999)), "locked quotes never expire");
    }

    @Test
    void executeRequiresLockedAndIsTerminal() {
        Quote quote = quote(Duration.ofSeconds(30));
        assertThrows(QuoteStateException.class, quote::execute);
        quote.lock(NOW);
        quote.execute();
        assertEquals(QuoteState.EXECUTED, quote.state());
        assertThrows(QuoteStateException.class, quote::execute);
        assertThrows(QuoteStateException.class, () -> quote.lock(NOW));
    }

    @Test
    void expireOnlyAppliesToQuotedQuotes() {
        Quote quote = quote(Duration.ofSeconds(30));
        quote.expire();
        assertEquals(QuoteState.EXPIRED, quote.state());
        assertThrows(QuoteStateException.class, quote::expire);
        assertThrows(QuoteStateException.class, () -> quote.lock(NOW));
        assertThrows(QuoteStateException.class, quote::execute);
    }

    @Test
    void lockedQuotesAreNeverAutoExpired() {
        Quote quote = quote(Duration.ofSeconds(30));
        quote.lock(NOW);
        assertThrows(QuoteStateException.class, quote::expire);
        assertEquals(QuoteState.LOCKED, quote.state());
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(FxDomainException.class, () -> Quote.quoted(null, SOURCE, RATE, 0, Duration.ofSeconds(30), NOW));
        assertThrows(FxDomainException.class, () -> Quote.quoted(" ", SOURCE, RATE, 0, Duration.ofSeconds(30), NOW));
        assertThrows(FxDomainException.class, () -> Quote.quoted("fxq_x", SOURCE, RATE, 0, Duration.ZERO, NOW));
        assertThrows(FxDomainException.class, () -> Quote.quoted("fxq_x", SOURCE, RATE, 0, Duration.ofSeconds(-1), NOW));
        assertThrows(NullPointerException.class,
                () -> Quote.quoted("fxq_x", null, RATE, 0, Duration.ofSeconds(30), NOW));
        assertThrows(NullPointerException.class,
                () -> Quote.quoted("fxq_x", SOURCE, null, 0, Duration.ofSeconds(30), NOW));
        assertThrows(NullPointerException.class, () -> Quote.quoted("fxq_x", SOURCE, RATE, 0, null, NOW));
        assertThrows(NullPointerException.class, () -> Quote.quoted("fxq_x", SOURCE, RATE, 0, Duration.ofSeconds(30), null));
        // source currency must match the rate's base currency
        assertThrows(CurrencyMismatchException.class,
                () -> Quote.quoted("fxq_x", Money.of(10000, "KES"), RATE, 0, Duration.ofSeconds(30), NOW));
    }

    @Test
    void rehydrateRestoresAnyStateAndValidatesItsInputs() {
        Quote quoted = Quote.quoted("fxq_x", SOURCE, RATE, 0, Duration.ofSeconds(30), NOW);
        assertEquals(QuoteState.QUOTED, Quote.rehydrate("fxq_x", "USD", "KES", SOURCE,
                Money.of(1270650, "KES"), RATE, 0, quoted.expiresAt(), NOW, QuoteState.QUOTED).state());

        assertEquals(QuoteState.EXECUTED, Quote.rehydrate("fxq_x", "USD", "KES", SOURCE,
                Money.of(1270650, "KES"), RATE, 0, quoted.expiresAt(), NOW, QuoteState.EXECUTED).state());

        assertThrows(FxDomainException.class, () -> Quote.rehydrate(null, "USD", "KES", SOURCE,
                Money.of(1270650, "KES"), RATE, 0, quoted.expiresAt(), NOW, QuoteState.QUOTED));
        assertThrows(FxDomainException.class, () -> Quote.rehydrate("fxq_x", " ", "KES", SOURCE,
                Money.of(1270650, "KES"), RATE, 0, quoted.expiresAt(), NOW, QuoteState.QUOTED));
        assertThrows(NullPointerException.class, () -> Quote.rehydrate("fxq_x", "USD", "KES",
                null, Money.of(1270650, "KES"), RATE, 0, quoted.expiresAt(), NOW, QuoteState.QUOTED));
        assertThrows(NullPointerException.class, () -> Quote.rehydrate("fxq_x", "USD", "KES",
                SOURCE, Money.of(1270650, "KES"), null, 0, quoted.expiresAt(), NOW, QuoteState.QUOTED));
        assertThrows(NullPointerException.class, () -> Quote.rehydrate("fxq_x", "USD", "KES",
                SOURCE, Money.of(1270650, "KES"), RATE, 0, quoted.expiresAt(), NOW, null));
        // persisted currencies must agree with the persisted money
        assertThrows(CurrencyMismatchException.class, () -> Quote.rehydrate("fxq_x", "USD", "KES",
                SOURCE, Money.of(1270650, "EUR"), RATE, 0, quoted.expiresAt(), NOW, QuoteState.QUOTED));
    }

    @Test
    void equalityIsIdentityBasedOnTheQuoteId() {
        Quote quote = quote(Duration.ofSeconds(30));
        assertEquals(quote, quote);
        assertEquals(quote, quote(Duration.ofSeconds(99)), "same id = same aggregate");
        assertEquals(quote.hashCode(), quote(Duration.ofSeconds(99)).hashCode());
        assertNotEquals(quote, null);
        assertNotEquals(quote, "fxq_x");
        assertNotEquals(quote, Quote.quoted("fxq_other", SOURCE, RATE, 0, Duration.ofSeconds(30), NOW));
    }
}
