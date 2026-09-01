package com.sharkpay.fx.domain;

import com.sharkpay.money.Currencies;

import java.util.Objects;

/**
 * One leg of a journal entry posted to the ledger: an account reference
 * (e.g. {@code wallet:usr_42:KES} or {@code fx-position:USD}), the leg's
 * currency, its signed-minor-unit amount (always non-negative here; the
 * {@link Direction} carries the sign) and the posting direction.
 *
 * @param accountRef  ledger account reference; real customer wallet refs are
 *                    supplied by the caller/integration layer (README
 *                    &#167;Account references)
 * @param currency    ISO-4217-style code from the supported set
 * @param amountMinor amount in minor units, non-negative
 * @param direction   DEBIT or CREDIT
 */
public record Leg(String accountRef, String currency, long amountMinor, Direction direction) {

    public Leg {
        if (accountRef == null || accountRef.isBlank()) {
            throw new FxDomainException("leg accountRef is required");
        }
        if (currency == null || !Currencies.isSupported(currency)) {
            throw new com.sharkpay.money.UnknownCurrencyException(String.valueOf(currency));
        }
        if (amountMinor < 0) {
            throw new FxDomainException("leg amountMinor must be >= 0: " + amountMinor);
        }
        Objects.requireNonNull(direction, "leg direction is required");
    }
}
