package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * The internal-side counterpart of a provider statement line, as shaped by
 * the {@code LedgerStatementPort} from the ledger's account-statement
 * surface (postings within the window, transaction keys, entry types). One
 * line aggregates the internal postings for one internal movement: the
 * principal amount and its fee.
 *
 * <p>{@code providerRef} is the match key against provider statement lines;
 * it is null for internal movements that never referenced a provider
 * transfer — those can never match and classify as MISSING_ON_PROVIDER
 * when they appear on a provider-linked account.</p>
 *
 * @param internalRef internal reference (transaction key / source ref)
 * @param providerRef provider-side transfer reference, or null
 * @param status      the internal status (canonical wire name)
 * @param amount      the principal movement (integer minor units)
 * @param fee         the fee charged for the movement
 * @param occurredAt  ledger-side timestamp of the movement
 */
public record InternalLedgerLine(String internalRef, String providerRef, String status, Money amount,
                                 Money fee, Instant occurredAt) {

    public InternalLedgerLine {
        Objects.requireNonNull(internalRef, "internalRef is required");
        if (internalRef.isBlank()) {
            throw new IllegalArgumentException("internal line internalRef must not be blank");
        }
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(fee, "fee is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
    }

    /** True when this line can match a provider statement line by ref. */
    public boolean isMatchable() {
        return providerRef != null && !providerRef.isBlank();
    }
}
