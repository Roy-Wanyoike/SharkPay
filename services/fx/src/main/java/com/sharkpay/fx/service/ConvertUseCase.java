package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.Conversion;
import com.sharkpay.fx.domain.ConversionState;
import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.fx.domain.IdempotencyConflictException;
import com.sharkpay.fx.domain.Leg;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.domain.QuoteExpiredException;
import com.sharkpay.fx.domain.QuoteState;
import com.sharkpay.fx.domain.QuoteStateException;
import com.sharkpay.fx.events.FxEvents;
import com.sharkpay.fx.ports.ConversionRepository;
import com.sharkpay.fx.ports.EventPublisher;
import com.sharkpay.fx.ports.IdempotencyStore;
import com.sharkpay.fx.ports.LedgerPort;
import com.sharkpay.fx.ports.QuoteRepository;
import com.sharkpay.fx.ports.StoredRequest;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Convert use-case: convert funds between two of the caller's wallets using
 * a LOCKED quote. Requires an Idempotency-Key; a duplicate key with the same
 * payload replays the original conversion (the ledger is posted EXACTLY
 * once), a duplicate key with a different payload is a 409 conflict.
 *
 * <p>Execution order (crash-safe by construction):
 * <ol>
 *   <li>reserve the Idempotency-Key (released again if the attempt fails)</li>
 *   <li>recompute the target amount from the quote's rate (deterministic
 *       integer math, must equal the quote's indicative target)</li>
 *   <li>post the 4-leg journal entry via {@link LedgerPort} with the ledger
 *       transaction key {@code fx:<conversionId>} (ledger-side idempotency)</li>
 *   <li>mark the quote EXECUTED, persist the conversion</li>
 *   <li>publish {@code fx.conversion.executed.v1}</li>
 * </ol>
 */
public final class ConvertUseCase {

    private final QuoteRepository quotes;
    private final ConversionRepository conversions;
    private final LedgerPort ledger;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final Clock clock;

    public ConvertUseCase(QuoteRepository quotes, ConversionRepository conversions, LedgerPort ledger,
                          IdempotencyStore idempotency, EventPublisher events, Clock clock) {
        this.quotes = Objects.requireNonNull(quotes, "quoteRepository is required");
        this.conversions = Objects.requireNonNull(conversions, "conversionRepository is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey     client Idempotency-Key (required, non-blank)
     * @param quoteId            the LOCKED quote to convert
     * @param sourceWalletRef      ledger account ref of the caller's base-currency wallet
     * @param destinationWalletRef ledger account ref of the caller's quote-currency wallet
     */
    public Result convert(String idempotencyKey, String quoteId, String sourceWalletRef, String destinationWalletRef) {
        requireText(idempotencyKey, "idempotency key");
        requireText(quoteId, "quote id");
        requireText(sourceWalletRef, "source wallet account ref");
        requireText(destinationWalletRef, "destination wallet account ref");
        String quote = quoteId.trim();
        String sourceWallet = sourceWalletRef.trim();
        String destinationWallet = destinationWalletRef.trim();
        String fingerprint = fingerprint(quote, sourceWallet, destinationWallet);

        Optional<StoredRequest> stored = idempotency.find(idempotencyKey.trim());
        if (stored.isPresent()) {
            StoredRequest request = stored.get();
            if (!request.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException();
            }
            Conversion original = conversions.findById(request.conversionId())
                    .orElseThrow(() -> new FxDomainException(
                            "conversion " + request.conversionId() + " referenced by idempotency key "
                                    + idempotencyKey + " is missing"));
            return new Result(original, true);
        }

        String conversionId = Ids.newConversionId();
        idempotency.put(idempotencyKey.trim(), new StoredRequest(fingerprint, conversionId));
        try {
            return new Result(execute(conversionId, quote, sourceWallet, destinationWallet), false);
        } catch (RuntimeException failure) {
            // Release the reservation so a retry can attempt again; the
            // ledger stays idempotent on its own transaction key anyway.
            idempotency.remove(idempotencyKey.trim());
            throw failure;
        }
    }

    private Conversion execute(String conversionId, String quoteId, String sourceWalletRef, String destinationWalletRef) {
        Quote quote = quotes.findById(quoteId)
                .orElseThrow(() -> new NoSuchElementException("quote " + quoteId + " not found"));
        java.time.Instant now = clock.instant();
        if (quote.isExpiredAt(now)) {
            throw new QuoteExpiredException(quoteId, quote.expiresAt(), now);
        }
        if (quote.state() != QuoteState.LOCKED) {
            throw new QuoteStateException(quoteId, quote.state(), "convert");
        }
        // Deterministic recomputation — must match the indicative target shown on the quote.
        com.sharkpay.fx.domain.Rate.ConversionResult converted = quote.rate().convert(quote.sourceAmount());
        if (!converted.target().equals(quote.targetAmount())) {
            throw new FxDomainException("quote " + quoteId + " target amount is inconsistent with its rate");
        }
        String ledgerTxnKey = ledgerTxnKey(conversionId);
        List<Leg> legs = Conversion.legsFor(sourceWalletRef, destinationWalletRef,
                quote.sourceAmount(), converted.target());
        String ledgerEntryId = ledger.postTransaction(ledgerTxnKey, legs);
        quote.execute();
        quotes.save(quote);
        Conversion conversion = new Conversion(conversionId, quote.id(), sourceWalletRef, destinationWalletRef,
                quote.sourceAmount(), converted.target(), quote.rate(), ledgerTxnKey, ledgerEntryId,
                ConversionState.EXECUTED, now);
        conversions.save(conversion);
        events.publish(FxEvents.conversionExecuted(conversion, clock.instant()));
        return conversion;
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new FxDomainException(what + " is required");
        }
    }

    /**
     * Canonical request fingerprint for idempotency-conflict detection:
     * quote id, source wallet ref, destination wallet ref, pipe-separated.
     */
    static String fingerprint(String quoteId, String sourceWalletRef, String destinationWalletRef) {
        return quoteId + "|" + sourceWalletRef + "|" + destinationWalletRef;
    }

    /** Ledger-side transaction key: unique per conversion. */
    static String ledgerTxnKey(String conversionId) {
        return "fx:" + conversionId;
    }

    /**
     * @param conversion the executed (or replayed) conversion
     * @param replay     true when this call was served from the idempotency
     *                   store (original response replayed, ledger untouched)
     */
    public record Result(Conversion conversion, boolean replay) {
    }
}
