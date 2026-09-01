package com.sharkpay.fx.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An executed FX conversion together with its 4-leg journal entry
 * (docs/ARCHITECTURE.md &#167;6).
 *
 * <h2>4-leg convention</h2>
 * <ol>
 *   <li>DEBIT the customer's base-currency wallet account</li>
 *   <li>CREDIT {@code fx-position:<BASE>}</li>
 *   <li>DEBIT {@code fx-position:<QUOTE>}</li>
 *   <li>CREDIT the customer's quote-currency wallet account</li>
 * </ol>
 * Legs 1+2 balance in the base currency and legs 3+4 balance in the quote
 * currency, so the entry satisfies the ledger's per-currency
 * debits-=-credits invariant. Real customer wallet account refs are
 * supplied by the caller/integration layer (this service's API takes wallet
 * account refs in the convert request); FX position account refs follow the
 * {@link AccountRefs} convention.
 */
public record Conversion(String id, String quoteId, String sourceWalletRef, String destinationWalletRef,
                         Money sourceAmount, Money targetAmount, Rate rate,
                         String ledgerTxnKey, String ledgerEntryId, ConversionState state, Instant createdAt) {

    public Conversion {
        requireText(id, "conversion id");
        requireText(quoteId, "quote id");
        requireText(sourceWalletRef, "source wallet account ref");
        requireText(destinationWalletRef, "destination wallet account ref");
        requireText(ledgerTxnKey, "ledger transaction key");
        requireText(ledgerEntryId, "ledger entry id");
        Objects.requireNonNull(sourceAmount, "sourceAmount is required");
        Objects.requireNonNull(targetAmount, "targetAmount is required");
        Objects.requireNonNull(rate, "rate is required");
        Objects.requireNonNull(state, "state is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (state != ConversionState.EXECUTED) {
            throw new FxDomainException("conversion state must be EXECUTED, got " + state);
        }
        if (!sourceAmount.currency().equals(rate.baseCurrency())
                || !targetAmount.currency().equals(rate.quoteCurrency())) {
            throw new CurrencyMismatchException(rate.baseCurrency() + "->" + rate.quoteCurrency(),
                    sourceAmount.currency() + "->" + targetAmount.currency());
        }
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new FxDomainException(what + " is required");
        }
    }

    /** The 4 legs of this conversion's journal entry (see class javadoc). */
    public List<Leg> legs() {
        return legsFor(sourceWalletRef, destinationWalletRef, sourceAmount, targetAmount);
    }

    /**
     * Builds the 4-leg FX journal entry for a conversion from source to
     * target money between two wallet account refs. Used both when posting
     * (before the conversion is persisted) and for verification/reconciliation.
     */
    public static List<Leg> legsFor(String sourceWalletRef, String destinationWalletRef, Money sourceAmount, Money targetAmount) {
        if (sourceWalletRef == null || sourceWalletRef.isBlank()) {
            throw new FxDomainException("source wallet account ref is required");
        }
        if (destinationWalletRef == null || destinationWalletRef.isBlank()) {
            throw new FxDomainException("destination wallet account ref is required");
        }
        Objects.requireNonNull(sourceAmount, "sourceAmount is required");
        Objects.requireNonNull(targetAmount, "targetAmount is required");
        return List.of(
                new Leg(sourceWalletRef, sourceAmount.currency(), sourceAmount.amountMinor(), Direction.DEBIT),
                new Leg(AccountRefs.fxPosition(sourceAmount.currency()), sourceAmount.currency(),
                        sourceAmount.amountMinor(), Direction.CREDIT),
                new Leg(AccountRefs.fxPosition(targetAmount.currency()), targetAmount.currency(),
                        targetAmount.amountMinor(), Direction.DEBIT),
                new Leg(destinationWalletRef, targetAmount.currency(), targetAmount.amountMinor(), Direction.CREDIT));
    }
}
