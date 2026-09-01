package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.testsupport.FxTestEnv;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The background sweeper delegates to {@link ExpireQuotesUseCase} on the
 * configured fixed-delay schedule ({@code fx.quote.expiry-sweep-interval-ms});
 * the scheduling metadata is pinned so the interval cannot silently drift.
 */
class ExpiredQuoteSweeperTest {

    private final FxTestEnv env = new FxTestEnv();

    @Test
    void sweepExpiresOverdueQuotesThroughTheUseCase() {
        Quote overdue = env.newQuote("USD", "KES", 10_000);
        ExpiredQuoteSweeper sweeper = new ExpiredQuoteSweeper(env.expireQuotes);

        env.clock.advance(Duration.ofSeconds(31));
        sweeper.sweep();

        assertThat(env.quotes.findById(overdue.id()).orElseThrow().state())
                .isEqualTo(QuoteState.EXPIRED);
    }

    @Test
    void sweepIsSafeToRepeatAndWhenNothingIsDue() {
        ExpiredQuoteSweeper sweeper = new ExpiredQuoteSweeper(env.expireQuotes);

        sweeper.sweep();
        sweeper.sweep();

        assertThat(env.quotes.findExpiredQuoted(env.clock.instant())).isEmpty();
    }

    @Test
    void requiresTheUseCaseAndCarriesTheScheduledFixedDelay() throws Exception {
        assertThatThrownBy(() -> new ExpiredQuoteSweeper(null))
                .isInstanceOf(NullPointerException.class);

        Scheduled scheduled = ExpiredQuoteSweeper.class.getMethod("sweep")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${fx.quote.expiry-sweep-interval-ms:5000}");
    }
}
