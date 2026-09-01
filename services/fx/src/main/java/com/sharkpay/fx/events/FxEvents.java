package com.sharkpay.fx.events;

import com.sharkpay.fx.api.MoneyJson;
import com.sharkpay.fx.api.RateJson;
import com.sharkpay.fx.domain.Conversion;
import com.sharkpay.fx.domain.Quote;

import java.time.Instant;

/**
 * Event factories for the two FX event types defined in
 * contracts/events/fx.v1.json. Payload field names use snake_case exactly
 * as specified by the schema (validated against
 * {@code additionalProperties: false}).
 */
public final class FxEvents {

    /** A quote was locked (its TTL is being consumed). */
    public static final String QUOTE_LOCKED = "fx.quote.locked.v1";

    /** The 4-leg conversion entry was posted atomically. */
    public static final String CONVERSION_EXECUTED = "fx.conversion.executed.v1";

    private FxEvents() {
    }

    /** Builds the {@code fx.quote.locked.v1} event for a quote. */
    public static CloudEvent quoteLocked(Quote quote, Instant occurredAt) {
        return new CloudEvent(EventIds.uuidV7().toString(), QUOTE_LOCKED, CloudEvent.SPECVERSION, CloudEvent.SOURCE,
                quote.id(), occurredAt,
                new QuoteLockedData(quote.id(), quote.baseCurrency(), quote.quoteCurrency(),
                        RateJson.of(quote.rate()), quote.expiresAt()));
    }

    /** Builds the {@code fx.conversion.executed.v1} event for a conversion. */
    public static CloudEvent conversionExecuted(Conversion conversion, Instant occurredAt) {
        return new CloudEvent(EventIds.uuidV7().toString(), CONVERSION_EXECUTED, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, conversion.id(), occurredAt,
                new ConversionExecutedData(conversion.id(), conversion.quoteId(),
                        MoneyJson.of(conversion.sourceAmount()), MoneyJson.of(conversion.targetAmount()),
                        conversion.ledgerEntryId()));
    }

    /** Payload of {@code fx.quote.locked.v1} (schema: quoteLockedData). */
    public record QuoteLockedData(String quote_id, String base_currency, String quote_currency, RateJson rate,
                                  Instant expires_at) {
    }

    /** Payload of {@code fx.conversion.executed.v1} (schema: conversionExecutedData). */
    public record ConversionExecutedData(String conversion_id, String quote_id, MoneyJson source_amount,
                                         MoneyJson target_amount, String entry_id) {
    }
}
