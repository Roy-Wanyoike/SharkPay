package com.sharkpay.fx.domain;

import com.sharkpay.money.Money;

import java.util.Objects;

/**
 * Mark-up (spread) policy in basis points, applied on top of the raw
 * provider rate. Pure integer math — no binary-fraction types.
 *
 * <h2>Quoted-rate formula</h2>
 * <pre>quoted = raw × (10 000 − markupBps) / 10 000   (exact rational)</pre>
 * The customer receives <em>less</em> quote currency than the indicative
 * market rate; the withheld spread is the platform's FX revenue and accrues
 * to the FX position accounts (observable as FX P&L in the ledger — that is
 * why no separate fee leg exists in the 4-leg entry).
 *
 * <h2>Fee/markup split</h2>
 * {@link #split(Money)} splits an indicative gross target amount (valued at
 * the raw rate) into customer and platform shares with
 * {@link Money#allocate(int[], int)} — largest-remainder, so no minor unit
 * is ever lost or created.
 */
public record MarkupPolicy(long markupBps) {

    /** 10 000 basis points = 100%. A 100% mark-up would zero the rate. */
    public static final long MAX_BPS = 9_999;

    public MarkupPolicy {
        if (markupBps < 0 || markupBps > MAX_BPS) {
            throw new FxDomainException("markup bps must be in [0, " + MAX_BPS + "]: " + markupBps);
        }
    }

    /**
     * Applies the mark-up to a raw rate: exact rational scaling by
     * {@code (10 000 − bps) / 10 000}. No rounding occurs anywhere.
     */
    public Rate applyTo(Rate raw) {
        Objects.requireNonNull(raw, "raw rate is required");
        return raw.scale(10_000 - markupBps, 10_000);
    }

    /**
     * Splits a gross indicative target amount (converted at the RAW rate)
     * into {@code [toCustomer, toPlatform]} using {@link Money#allocate}
     * with ratios {@code (10 000 − bps) : bps} over 10 000 — largest
     * remainder method, deterministic (ties break to the customer part),
     * parts always sum to the gross exactly.
     */
    public Money[] split(Money grossTarget) {
        Objects.requireNonNull(grossTarget, "gross target money is required");
        return grossTarget.allocate(new int[]{(int) (10_000 - markupBps), (int) markupBps}, 10_000);
    }
}
