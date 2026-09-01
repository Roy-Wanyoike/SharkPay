package com.sharkpay.wallet.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A reservation of wallet funds placed by a payments/payouts/transfer flow.
 * The hold ledger is the wallet service's own funds-control state:
 *
 * <ul>
 *   <li>{@code placeHold} (constructor): ACTIVE, reserves the full
 *       {@code amount} — the caller must have verified
 *       {@code amount <= available} first (never total below zero);</li>
 *   <li>{@code release}: ACTIVE → RELEASED, the full amount returns to
 *       available;</li>
 *   <li>{@code capture(amount)}: ACTIVE → CAPTURED — {@code amount} becomes a
 *       settled debit and the remainder is released immediately (partial
 *       capture). Terminal: {@code captured + released == amount} always.</li>
 * </ul>
 *
 * <p>The settled debit itself arrives as a {@code ledger.posting.committed.v1}
 * event (the ledger is the sole money authority); the hold only stops the
 * reserved funds from being double-spent.
 */
public final class Hold {

    /** Public hold id pattern (contracts/events/wallet.holds.v1.json). */
    public static final Pattern ID_PATTERN = Pattern.compile("^hld_[0-9A-Za-z]{20,}$");

    private final String id;
    private final String walletId;
    private final Money amount;
    private final Source source;
    private final UUID sourceRef;
    private final String reason;
    private final Instant createdAt;
    private HoldState state;
    private Money captured;
    private Money released;
    private Instant updatedAt;

    public Hold(String id, String walletId, Money amount, Source source, UUID sourceRef,
                String reason, HoldState state, Money captured, Money released,
                Instant createdAt, Instant updatedAt) {
        this.id = requireId(id);
        this.walletId = requireWalletId(walletId);
        this.amount = requirePositive(amount);
        this.source = Objects.requireNonNull(source, "source is required");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef is required");
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        this.state = Objects.requireNonNull(state, "state is required");
        this.captured = captured == null ? Money.zero(this.amount.currency()) : captured;
        this.released = released == null ? Money.zero(this.amount.currency()) : released;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        requireSameCurrency(this.captured, "captured");
        requireSameCurrency(this.released, "released");
        requireValidSplit();
    }

    /** Places a new ACTIVE hold of {@code amount} on {@code walletId}. */
    public static Hold place(String id, String walletId, Money amount, Source source,
                             UUID sourceRef, String reason, Instant at) {
        return new Hold(id, walletId, amount, source, sourceRef, reason,
                HoldState.ACTIVE, null, null, at, at);
    }

    /** Releases the whole reserved amount back to available. ACTIVE → RELEASED. */
    public void release(Instant at) {
        requireActive("release");
        state = HoldState.RELEASED;
        released = amount;
        updatedAt = at;
    }

    /**
     * Captures {@code captureAmount} as a settled debit and releases the
     * remainder. ACTIVE → CAPTURED.
     *
     * @throws CurrencyMismatchException when the capture currency differs
     *         from the hold currency
     * @throws IllegalArgumentException when the amount is not positive or
     *         exceeds the reserved amount
     */
    public void capture(Money captureAmount, Instant at) {
        requireActive("capture");
        Objects.requireNonNull(captureAmount, "captureAmount is required");
        if (!captureAmount.currency().equals(amount.currency())) {
            throw new CurrencyMismatchException(amount.currency(), captureAmount.currency());
        }
        if (!captureAmount.isPositive()) {
            throw new IllegalArgumentException("capture amount must be positive: " + captureAmount);
        }
        if (captureAmount.compareTo(amount) > 0) {
            throw new IllegalArgumentException("capture amount " + captureAmount
                    + " exceeds the reserved amount " + amount);
        }
        state = HoldState.CAPTURED;
        captured = captureAmount;
        released = amount.subtract(captureAmount);
        updatedAt = at;
        requireValidSplit();
    }

    private void requireValidSplit() {
        boolean terminal = state != HoldState.ACTIVE;
        boolean activeZero = captured.isZero() && released.isZero();
        if (terminal && (activeZero || !captured.add(released).equals(amount))) {
            throw new IllegalArgumentException("terminal hold must split its amount exactly: captured="
                    + captured + " released=" + released + " amount=" + amount);
        }
        if (!terminal && !activeZero) {
            throw new IllegalArgumentException("active hold must not have captured/released amounts");
        }
    }

    /** Only ACTIVE holds can transition; the message names the actual terminal state. */
    private void requireActive(String verb) {
        if (state != HoldState.ACTIVE) {
            throw new HoldStateException(id, state, verb + " a " + state.wireName() + " hold");
        }
    }

    private static Money requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("hold amount must be positive: " + amount);
        }
        return amount;
    }

    private void requireSameCurrency(Money part, String what) {
        if (!part.currency().equals(amount.currency())) {
            throw new CurrencyMismatchException(amount.currency(), part.currency());
        }
    }

    private static String requireId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "hold id must match " + ID_PATTERN.pattern() + ": " + id);
        }
        return id;
    }

    private static String requireWalletId(String walletId) {
        if (walletId == null || !Wallet.ID_PATTERN.matcher(walletId).matches()) {
            throw new IllegalArgumentException(
                    "wallet id must match " + Wallet.ID_PATTERN.pattern() + ": " + walletId);
        }
        return walletId;
    }

    public String id() {
        return id;
    }

    public String walletId() {
        return walletId;
    }

    /** The originally reserved amount (always the full amount, any state). */
    public Money amount() {
        return amount;
    }

    public Source source() {
        return source;
    }

    public UUID sourceRef() {
        return sourceRef;
    }

    /** Audit note supplied when the hold was placed (nullable). */
    public String reason() {
        return reason;
    }

    public HoldState state() {
        return state;
    }

    /** Settled amount; zero while the hold is ACTIVE or RELEASED. */
    public Money captured() {
        return captured;
    }

    /** Returned-to-available amount; zero while the hold is ACTIVE. */
    public Money released() {
        return released;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Hold hold)) {
            return false;
        }
        return id.equals(hold.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Hold[" + id + " wallet=" + walletId + " " + amount + " " + state.wireName() + "]";
    }
}
