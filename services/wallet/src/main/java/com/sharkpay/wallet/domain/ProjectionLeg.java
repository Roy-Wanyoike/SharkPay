package com.sharkpay.wallet.domain;

import com.sharkpay.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One wallet leg of a committed ledger journal entry, as consumed from a
 * {@code ledger.posting.committed.v1} event — the input to the balance
 * projection. The running {@code balance_after} values are derived by
 * {@link PostingSequence} in posting order, so this record carries the raw
 * leg only.
 *
 * @param postingId  globally monotonic ledger posting sequence (bigserial)
 * @param entryId    journal entry id
 * @param entryType  capture | hold | release | reversal | fee | fx | adjustment
 * @param direction  debit (decreases the wallet) or credit (increases it)
 * @param amount     positive minor-unit amount of the leg
 * @param source     owning domain of the entry
 * @param sourceRef  business object the entry belongs to
 * @param reason     operator/system note (adjustments, reversals; nullable)
 * @param occurredAt ledger commit time of the entry
 */
public record ProjectionLeg(long postingId, UUID entryId, String entryType, Direction direction,
                            Money amount, Source source, UUID sourceRef, String reason,
                            Instant occurredAt) {

    public ProjectionLeg {
        Objects.requireNonNull(entryId, "entryId is required");
        Objects.requireNonNull(entryType, "entryType is required");
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(sourceRef, "sourceRef is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        if (postingId < 1) {
            throw new IllegalArgumentException("postingId must be >= 1: " + postingId);
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("leg amount must be positive: " + amount);
        }
    }
}
