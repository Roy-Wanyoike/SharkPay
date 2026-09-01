package com.sharkpay.fx.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Background sweeper that periodically expires overdue QUOTED quotes.
 * Scheduling is enabled on {@code FxApplication} (@EnableScheduling); the
 * interval is configurable via {@code fx.quote.expiry-sweep-interval-ms}.
 */
@Component
public final class ExpiredQuoteSweeper {

    private final ExpireQuotesUseCase expireQuotes;

    public ExpiredQuoteSweeper(ExpireQuotesUseCase expireQuotes) {
        this.expireQuotes = Objects.requireNonNull(expireQuotes, "expireQuotesUseCase is required");
    }

    @Scheduled(fixedDelayString = "${fx.quote.expiry-sweep-interval-ms:5000}")
    public void sweep() {
        expireQuotes.expireOverdue();
    }
}
