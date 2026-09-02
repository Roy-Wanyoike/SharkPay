package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quota decision value: allow / per-minute exceeded / monthly exceeded with
 * the retry-after seconds, and the OptionalLong projection.
 */
class QuotaDecisionTest {

    @Test
    void allowCarriesNoRetryAfter() {
        QuotaDecision decision = QuotaDecision.allow();
        assertTrue(decision.allowed());
        assertFalse(decision.monthly());
        assertTrue(decision.retryAfter().isEmpty());
        assertEquals(OptionalLong.empty(), decision.retryAfter());
    }

    @Test
    void perMinuteExceededCarriesTheBurstWindowRetryAfter() {
        QuotaDecision decision = QuotaDecision.perMinuteExceeded(30L);
        assertFalse(decision.allowed());
        assertFalse(decision.monthly());
        assertEquals(OptionalLong.of(30L), decision.retryAfter());
        assertEquals(30L, decision.retryAfter().getAsLong());
    }

    @Test
    void monthlyExceededIsFlaggedDistinctly() {
        QuotaDecision decision = QuotaDecision.monthlyExceeded(86_400L);
        assertFalse(decision.allowed());
        assertTrue(decision.monthly());
        assertEquals(OptionalLong.of(86_400L), decision.retryAfter());
        assertEquals(86_400L, decision.retryAfterSeconds());
    }

    @Test
    void decisionsWithTheSameFieldsAreEqual() {
        assertEquals(QuotaDecision.perMinuteExceeded(10L), QuotaDecision.perMinuteExceeded(10L));
        assertEquals(QuotaDecision.allow(), QuotaDecision.allow());
        assertFalse(QuotaDecision.perMinuteExceeded(10L).equals(QuotaDecision.monthlyExceeded(10L)));
    }
}
