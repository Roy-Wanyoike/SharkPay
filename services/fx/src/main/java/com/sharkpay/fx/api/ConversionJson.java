package com.sharkpay.fx.api;

import com.sharkpay.fx.domain.Conversion;

import java.time.Instant;

/**
 * Conversion resource (contracts/openapi/v1/fx.yaml Conversion). V1 converts
 * synchronously: {@code state=EXECUTED} and {@code entry_id} is the ledger
 * journal entry id of the 4-leg posting.
 */
public record ConversionJson(String id, String state, String quote_id, String source_wallet,
                             String destination_wallet, MoneyJson source_amount, MoneyJson target_amount,
                             RateJson rate, String entry_id, Instant created_at) {

    public static ConversionJson of(Conversion conversion) {
        return new ConversionJson(conversion.id(), conversion.state().name(), conversion.quoteId(),
                conversion.sourceWalletRef(), conversion.destinationWalletRef(),
                MoneyJson.of(conversion.sourceAmount()), MoneyJson.of(conversion.targetAmount()),
                RateJson.of(conversion.rate()), conversion.ledgerEntryId(), conversion.createdAt());
    }
}
