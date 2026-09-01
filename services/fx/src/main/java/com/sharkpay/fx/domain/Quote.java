package com.sharkpay.fx.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A TTL'd FX quote (docs/PRD.md &#167;7 D6, docs/STATE-MACHINES.md &#167;4).
 *
 * <p>The quote fixes the source amount, the quoted rate (raw provider rate
 * after the mark-up policy) and the indicative target amount derived from
 * that rate with the documented truncation policy. Locking inside the TTL
 * guarantees the rate; locked quotes never auto-expire. Executing posts the
 * 4-leg conversion entry.
 */
public final class Quote {

    private final String id;
    private final String baseCurrency;
    private final String quoteCurrency;
    private final Money sourceAmount;
    private final Money targetAmount;
    private final Rate rate;
    private final long markupBps;
    private final Instant expiresAt;
    private final Instant createdAt;
    private QuoteState state;

    private Quote(String id, String baseCurrency, String quoteCurrency, Money sourceAmount, Money targetAmount,
                  Rate rate, long markupBps, Instant expiresAt, Instant createdAt, QuoteState state) {
        this.id = id;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.sourceAmount = sourceAmount;
        this.targetAmount = targetAmount;
        this.rate = rate;
        this.markupBps = markupBps;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.state = state;
    }

    /**
     * Creates a QUOTED quote. The indicative target amount is derived from
     * the quoted rate via {@link Rate#convert(Money)} (truncate + reported
     * dust).
     */
    public static Quote quoted(String id, Money sourceAmount, Rate quotedRate, long markupBps, Duration ttl, Instant now) {
        if (id == null || id.isBlank()) {
            throw new FxDomainException("quote id is required");
        }
        Objects.requireNonNull(sourceAmount, "sourceAmount is required");
        Objects.requireNonNull(quotedRate, "quotedRate is required");
        Objects.requireNonNull(ttl, "ttl is required");
        Objects.requireNonNull(now, "now is required");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new FxDomainException("quote ttl must be positive: " + ttl);
        }
        if (!quotedRate.baseCurrency().equals(sourceAmount.currency())) {
            throw new CurrencyMismatchException(sourceAmount.currency(), quotedRate.baseCurrency());
        }
        Rate.ConversionResult indicative = quotedRate.convert(sourceAmount);
        return new Quote(id, sourceAmount.currency(), quotedRate.quoteCurrency(), sourceAmount,
                indicative.target(), quotedRate, markupBps, now.plus(ttl), now, QuoteState.QUOTED);
    }

    /**
     * Rehydrates a quote in ANY state from persisted values (no state
     * transitions, no recomputation — the stored target amount is trusted
     * as-is). Used by the storage adapter; the convert use-case still
     * recomputes the target from the rate before posting, so a corrupted
     * row can never silently move wrong money.
     */
    public static Quote rehydrate(String id, String baseCurrency, String quoteCurrency, Money sourceAmount,
                                  Money targetAmount, Rate rate, long markupBps, Instant expiresAt,
                                  Instant createdAt, QuoteState state) {
        if (id == null || id.isBlank()) {
            throw new FxDomainException("quote id is required");
        }
        if (baseCurrency == null || baseCurrency.isBlank() || quoteCurrency == null || quoteCurrency.isBlank()) {
            throw new FxDomainException("quote currencies are required");
        }
        Objects.requireNonNull(sourceAmount, "sourceAmount is required");
        Objects.requireNonNull(targetAmount, "targetAmount is required");
        Objects.requireNonNull(rate, "rate is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(state, "state is required");
        if (!baseCurrency.equals(sourceAmount.currency()) || !quoteCurrency.equals(targetAmount.currency())) {
            throw new CurrencyMismatchException(baseCurrency + "->" + quoteCurrency,
                    sourceAmount.currency() + "->" + targetAmount.currency());
        }
        return new Quote(id, baseCurrency, quoteCurrency, sourceAmount, targetAmount, rate, markupBps,
                expiresAt, createdAt, state);
    }

    /**
     * Locks the quote (rate guarantee). Only legal while QUOTED and inside
     * the TTL; locking a LOCKED quote is an idempotent no-op.
     *
     * @return true iff the QUOTED&#8594;LOCKED transition happened
     *         (i.e. this call consumed the TTL)
     */
    public boolean lock(Instant now) {
        Objects.requireNonNull(now, "now is required");
        switch (state) {
            case QUOTED -> {
                if (isExpiredAt(now)) {
                    throw new QuoteExpiredException(id, expiresAt, now);
                }
                state = QuoteState.LOCKED;
                return true;
            }
            case LOCKED -> {
                return false;
            }
            default -> throw new QuoteStateException(id, state, "lock");
        }
    }

    /**
     * LOCKED&#8594;EXECUTED — called after the 4-leg entry has been posted
     * to the ledger.
     */
    public void execute() {
        if (state != QuoteState.LOCKED) {
            throw new QuoteStateException(id, state, "execute");
        }
        state = QuoteState.EXECUTED;
    }

    /**
     * QUOTED&#8594;EXPIRED — only the expiry sweep may do this; LOCKED
     * quotes are rate-guaranteed and never auto-expired.
     */
    public void expire() {
        if (state != QuoteState.QUOTED) {
            throw new QuoteStateException(id, state, "expire");
        }
        state = QuoteState.EXPIRED;
    }

    /** True while QUOTED and the TTL has fully elapsed ({@code now >= expiresAt}). */
    public boolean isExpiredAt(Instant now) {
        return state == QuoteState.QUOTED && !now.isBefore(expiresAt);
    }

    public String id() {
        return id;
    }

    public String baseCurrency() {
        return baseCurrency;
    }

    public String quoteCurrency() {
        return quoteCurrency;
    }

    public Money sourceAmount() {
        return sourceAmount;
    }

    public Money targetAmount() {
        return targetAmount;
    }

    public Rate rate() {
        return rate;
    }

    public long markupBps() {
        return markupBps;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public QuoteState state() {
        return state;
    }

    /**
     * Identity equality on the public quote id (the same aggregate
     * rehydrated from persistence equals itself — mirrors the wallet
     * service's Wallet).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Quote quote)) {
            return false;
        }
        return id.equals(quote.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Quote{" + id + " " + baseCurrency + "->" + quoteCurrency + " " + sourceAmount + "->" + targetAmount
                + " rate=" + rate + " state=" + state + " expiresAt=" + expiresAt + "}";
    }
}
