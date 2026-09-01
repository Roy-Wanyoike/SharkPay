package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.testsupport.FxTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expiry sweep (WP-7, docs/STATE-MACHINES.md §4): QUOTED quotes whose TTL
 * elapsed transition to EXPIRED; LOCKED quotes are never touched (their
 * rate is guaranteed — expiry of a locked quote would be a p1 incident).
 */
class ExpireQuotesUseCaseTest {

    private final FxTestEnv env = new FxTestEnv();

    @Test
    void sweepsNothingWhenNoQuoteIsOverdue() {
        env.newQuote("USD", "KES", 10_000);
        assertThat(env.expireQuotes.expireOverdue()).isZero();
        assertThat(env.quotes.findExpiredQuoted(env.clock.instant())).isEmpty();
    }

    @Test
    void expiresOverdueQuotedQuotes() {
        Quote live = env.newQuote("USD", "KES", 10_000);
        Quote overdue = env.newQuote("EUR", "KES", 5_000);

        env.clock.advance(Duration.ofSeconds(31)); // default TTL 30s
        int swept = env.expireQuotes.expireOverdue();

        assertThat(swept).isEqualTo(2);
        assertThat(env.quotes.findById(live.id()).orElseThrow().state()).isEqualTo(QuoteState.EXPIRED);
        assertThat(env.quotes.findById(overdue.id()).orElseThrow().state()).isEqualTo(QuoteState.EXPIRED);

        // the sweep is idempotent: nothing left to expire
        assertThat(env.expireQuotes.expireOverdue()).isZero();
    }

    @Test
    void lockedQuotesAreNeverSweptToExpired() {
        Quote locked = env.newLockedQuote("USD", "KES", 10_000);

        env.clock.advance(Duration.ofHours(8));
        assertThat(env.expireQuotes.expireOverdue()).isZero();
        assertThat(env.quotes.findById(locked.id()).orElseThrow().state()).isEqualTo(QuoteState.LOCKED);

        // and a locked quote still converts far beyond its original TTL
        assertThat(env.convert.convert("expire-sweep-1", locked.id(), "src", "dst").replay())
                .isFalse();
    }

    @Test
    void executedAndExpiredQuotesAreNotReswept() {
        Quote executed = env.newLockedQuote("USD", "KES", 10_000);
        env.convert.convert("expire-sweep-2", executed.id(), "src", "dst");
        env.clock.advance(Duration.ofSeconds(31));

        assertThat(env.expireQuotes.expireOverdue()).isZero();
        assertThat(env.quotes.findById(executed.id()).orElseThrow().state())
                .isEqualTo(QuoteState.EXECUTED);
    }
}
