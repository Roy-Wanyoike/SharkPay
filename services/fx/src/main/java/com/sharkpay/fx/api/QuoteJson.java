package com.sharkpay.fx.api;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.service.CreateQuoteUseCase;

import java.time.Instant;

/**
 * Quote resource (contracts/openapi/v1/fx.yaml Quote).
 */
public record QuoteJson(String id, String state, String base_currency, String quote_currency,
                        MoneyJson source_amount, MoneyJson target_amount, RateJson rate,
                        Instant expires_at, Instant created_at) {

    public static QuoteJson of(Quote quote) {
        return new QuoteJson(quote.id(), quote.state().name(), quote.baseCurrency(), quote.quoteCurrency(),
                MoneyJson.of(quote.sourceAmount()), MoneyJson.of(quote.targetAmount()), RateJson.of(quote.rate()),
                quote.expiresAt(), quote.createdAt());
    }

    static QuoteJson of(CreateQuoteUseCase.CreateQuoteResult result) {
        return of(result.quote());
    }
}
