package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Glob event-type matching for webhook subscriptions. */
class EventPatternTest {

    @Test
    void exactNamesMatchOnlyThemselves() {
        EventPattern pattern = EventPattern.of("payment.succeeded");
        assertTrue(pattern.matches("payment.succeeded"));
        assertFalse(pattern.matches("payment.created"));
        assertFalse(pattern.matches("payments.payment.succeeded.v1"));
    }

    @Test
    void starGlobsMatchWithinTheDottedName() {
        assertTrue(EventPattern.of("payment.*").matches("payment.succeeded"));
        assertTrue(EventPattern.of("payment.*").matches("payment.pending_provider"));
        assertFalse(EventPattern.of("payment.*").matches("payout.succeeded"));
        assertFalse(EventPattern.of("payment.*").matches("payment"));
    }

    @Test
    void bareStarMatchesEverything() {
        EventPattern star = EventPattern.of("*");
        assertTrue(star.matches("payment.succeeded"));
        assertTrue(star.matches("wallet.balance.changed"));
        assertTrue(star.matches("risk.case.opened"));
    }

    @Test
    void prefixGlobsMatchOnlyThatPrefix() {
        assertTrue(EventPattern.of("payment.s*").matches("payment.succeeded"));
        assertFalse(EventPattern.of("payment.s*").matches("payment.created"));
        assertFalse(EventPattern.of("payment.s*").matches("payment.expired"));
    }

    @Test
    void multiSegmentGlobs() {
        assertTrue(EventPattern.of("*.succeeded").matches("payment.succeeded"));
        assertTrue(EventPattern.of("*.succeeded").matches("payout.succeeded"));
        assertFalse(EventPattern.of("*.succeeded").matches("payment.created"));
        assertTrue(EventPattern.of("fx.*").matches("fx.quote.locked"));
        assertTrue(EventPattern.of("fx.*").matches("fx.conversion.executed"));
        assertFalse(EventPattern.of("fx.*").matches("wallet.balance.changed"));
    }

    @Test
    void invalidPatternsAreRejectedFailClosed() {
        assertThrows(InvalidEventTypesException.class, () -> EventPattern.of(""));
        assertThrows(InvalidEventTypesException.class, () -> EventPattern.of(null));
        assertThrows(InvalidEventTypesException.class, () -> EventPattern.of("Payment.*"));
        assertThrows(InvalidEventTypesException.class, () -> EventPattern.of("payment.."));
        assertThrows(InvalidEventTypesException.class, () -> EventPattern.of(".payment"));
        assertThrows(InvalidEventTypesException.class, () -> EventPattern.of("payment.created."));
        assertThrows(InvalidEventTypesException.class, () -> EventPattern.of("pay ment.*"));
        assertDoesNotThrow(() -> EventPattern.of("payment.pending_provider"));
    }

    @Test
    void valueEqualityOnPatternText() {
        assertTrue(EventPattern.of("payment.*").equals(EventPattern.of("payment.*")));
        assertFalse(EventPattern.of("payment.*").equals(EventPattern.of("payout.*")));
        assertEquals("payment.*", EventPattern.of("payment.*").pattern());
        assertEquals("payment.*", EventPattern.of("payment.*").toString());
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
