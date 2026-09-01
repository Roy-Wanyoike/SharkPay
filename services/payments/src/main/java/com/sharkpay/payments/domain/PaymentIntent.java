package com.sharkpay.payments.domain;

import com.sharkpay.money.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PaymentIntent aggregate (WP-5 core). Owns the exact payment state machine
 * (docs/STATE-MACHINES.md §1 — "any transition not listed is a bug"), the
 * computed fee (never floated, {@link FeePolicy}), the idempotency key, the
 * expiry deadline, provider references and the audit timestamps. Money is
 * {@code sharkpay-money} minor-unit integers only.
 *
 * <p>Transitions validate legality and append to an in-aggregate pending
 * transition log that the repository drains on save (append-only
 * {@code payment_state_transitions}). Money side effects themselves (hold /
 * release / capture / reversal at the wallet and ledger) are performed by the
 * application layer BEFORE the transition is applied, and their entry ids are
 * threaded into the transition rows — money state and payment state stay
 * aligned (§7.4).</p>
 *
 * <p>Mutation methods return {@code this} for call chaining in use-cases.</p>
 */
public final class PaymentIntent {

    /** Public intent id pattern (payments.yaml / payments.payment.v1.json). */
    public static final Pattern ID_PATTERN = Pattern.compile("^pay_[0-9A-Za-z]{20,}$");

    private final String id;
    private final UUID internalId;
    private final UUID principalId;
    private final String sourceWalletId;
    private final Destination destination;
    private final Money amount;
    private final Money fee;
    private final Rail rail;
    private final String idempotencyKey;
    private final Instant expiresAt;
    private final Map<String, String> metadata;
    private final Instant createdAt;

    private PaymentState state;
    private String provider;
    private String providerRef;
    private String holdId;
    private UUID holdEntryId;
    private UUID captureEntryId;
    private UUID releaseEntryId;
    private UUID reversalEntryId;
    private Money reversedAmount;
    private String failureReason;
    private Instant updatedAt;
    private long transitionSeq;
    private final List<StateTransition> pendingTransitions = new ArrayList<>();

    private PaymentIntent(String id, UUID internalId, UUID principalId, String sourceWalletId,
                          Destination destination, Money amount, Money fee, Rail rail,
                          PaymentState state, String idempotencyKey, Instant expiresAt,
                          Map<String, String> metadata, String provider, String providerRef,
                          String holdId, UUID holdEntryId, UUID captureEntryId,
                          UUID releaseEntryId, UUID reversalEntryId, Money reversedAmount,
                          String failureReason, Instant createdAt, Instant updatedAt,
                          long transitionSeq) {
        this.id = requireId(id);
        this.internalId = Objects.requireNonNull(internalId, "internalId is required");
        this.principalId = Objects.requireNonNull(principalId, "principalId is required");
        this.sourceWalletId = sourceWalletId == null || sourceWalletId.isBlank()
                ? null : sourceWalletId.trim();
        this.destination = Objects.requireNonNull(destination, "destination is required");
        this.amount = requirePositive(amount);
        this.fee = Objects.requireNonNull(fee, "fee is required");
        if (fee.isNegative()) {
            throw new IllegalArgumentException("fee must be non-negative: " + fee);
        }
        if (!fee.currency().equals(amount.currency())) {
            throw new com.sharkpay.money.CurrencyMismatchException(amount.currency(), fee.currency());
        }
        this.rail = Objects.requireNonNull(rail, "rail is required");
        this.state = Objects.requireNonNull(state, "state is required");
        this.idempotencyKey = requireKey(idempotencyKey);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        this.transitionSeq = transitionSeq;
        this.provider = blankToNull(provider);
        this.providerRef = blankToNull(providerRef);
        this.holdId = blankToNull(holdId);
        this.holdEntryId = holdEntryId;
        this.captureEntryId = captureEntryId;
        this.releaseEntryId = releaseEntryId;
        this.reversalEntryId = reversalEntryId;
        this.reversedAmount = reversedAmount;
        this.failureReason = blankToNull(failureReason);
    }

    /** Creates a fresh intent in CREATED (fee already computed by the caller). */
    public static PaymentIntent newIntent(String id, UUID internalId, UUID principalId,
                                          String sourceWalletId, Destination destination,
                                          Money amount, Money fee, String idempotencyKey,
                                          Rail rail, Instant expiresAt,
                                          Map<String, String> metadata, Instant createdAt) {
        PaymentIntent intent = new PaymentIntent(id, internalId, principalId, sourceWalletId,
                destination, amount, fee, rail, PaymentState.CREATED, idempotencyKey, expiresAt,
                metadata, null, null, null, null, null, null, null, null, null, createdAt,
                createdAt, 0);
        intent.append(null, PaymentState.CREATED, "created rail=" + rail.wireName(), null,
                createdAt);
        return intent;
    }

    /** Rehydrates the full aggregate from storage (all fields). */
    public static PaymentIntent rehydrate(String id, UUID internalId, UUID principalId,
                                          String sourceWalletId, Destination destination,
                                          Money amount, Money fee, Rail rail,
                                          PaymentState state, String idempotencyKey,
                                          Instant expiresAt, Map<String, String> metadata,
                                          String provider, String providerRef, String holdId,
                                          UUID holdEntryId, UUID captureEntryId,
                                          UUID releaseEntryId, UUID reversalEntryId,
                                          Money reversedAmount, String failureReason,
                                          Instant createdAt, Instant updatedAt,
                                          long transitionSeq) {
        return new PaymentIntent(id, internalId, principalId, sourceWalletId, destination,
                amount, fee, rail, state, idempotencyKey, expiresAt, metadata, provider,
                providerRef, holdId, holdEntryId, captureEntryId, releaseEntryId, reversalEntryId,
                reversedAmount, failureReason, createdAt, updatedAt, transitionSeq);
    }

    // ── state machine transitions ─────────────────────────────────────────

    /** CREATED → PENDING_PROVIDER (risk pass + hold placed). */
    public PaymentIntent markPendingProvider(String holdId, UUID holdEntryId, Instant at) {
        // arguments validated BEFORE the transition: a rejected call must
        // never leave the aggregate half-mutated
        this.holdId = requireSet(holdId, "holdId");
        transition(PaymentState.PENDING_PROVIDER, "risk_pass_hold_placed", holdEntryId, at);
        this.holdEntryId = holdEntryId;
        return this;
    }

    /** CREATED → BLOCKED (risk deny; no money moved). */
    public PaymentIntent markBlocked(String reason, Instant at) {
        transition(PaymentState.BLOCKED, "risk_deny: " + reason, null, at);
        return this;
    }

    /** CREATED/PENDING_PROVIDER → CANCELLED (user/API; hold already released). */
    public PaymentIntent markCancelled(UUID releaseEntryId, Instant at) {
        transition(PaymentState.CANCELLED, "user_cancel", releaseEntryId, at);
        this.releaseEntryId = coalesce(this.releaseEntryId, releaseEntryId);
        return this;
    }

    /** PENDING_PROVIDER → PROCESSING (provider accepted; settlement in flight). */
    public PaymentIntent markProcessing(Instant at) {
        transition(PaymentState.PROCESSING, "provider_accepted", null, at);
        return this;
    }

    /** PENDING_PROVIDER → EXPIRED (TTL; hold already released). */
    public PaymentIntent markExpired(UUID releaseEntryId, Instant at) {
        transition(PaymentState.EXPIRED, "ttl_elapsed", releaseEntryId, at);
        this.releaseEntryId = coalesce(this.releaseEntryId, releaseEntryId);
        return this;
    }

    /** PENDING_PROVIDER/PROCESSING → FAILED (reject/hard error; compensation done). */
    public PaymentIntent markFailed(String reason, UUID releaseEntryId, Instant at) {
        transition(PaymentState.FAILED, reason, releaseEntryId, at);
        this.failureReason = reason;
        this.releaseEntryId = coalesce(this.releaseEntryId, releaseEntryId);
        return this;
    }

    /** PROCESSING → SUCCEEDED (rail confirmed; hold captured to settled). */
    public PaymentIntent markSucceeded(UUID captureEntryId, Instant at) {
        // validate before the transition (no half-mutated aggregate)
        requireSet(captureEntryId, "captureEntryId");
        transition(PaymentState.SUCCEEDED, "rail_confirmed", captureEntryId, at);
        this.captureEntryId = captureEntryId;
        return this;
    }

    /**
     * SUCCEEDED/FAILED → REVERSED (provider/ops reversal). Guard: the
     * reversal amount must be ≤ the captured amount (the caller validated
     * against {@link #capturableAmount()}).
     */
    public PaymentIntent markReversed(String reason, UUID reversalEntryId, Money reversedAmount,
                                      Instant at) {
        // validate the full argument set before the transition (no
        // half-mutated aggregate on a rejected call)
        requireSet(reversalEntryId, "reversalEntryId");
        Objects.requireNonNull(reversedAmount, "reversedAmount is required");
        if (reversedAmount.isNegative()) {
            throw new IllegalArgumentException("reversal amount must be non-negative");
        }
        if (reversedAmount.currency() != null && !reversedAmount.currency().equals(amount.currency())) {
            throw new com.sharkpay.money.CurrencyMismatchException(amount.currency(),
                    reversedAmount.currency());
        }
        transition(PaymentState.REVERSED, reason, reversalEntryId, at);
        this.reversalEntryId = reversalEntryId;
        this.reversedAmount = reversedAmount;
        return this;
    }

    /**
     * Records the provider hand-off (routing outcome) without a state change:
     * the intent stays PENDING_PROVIDER while the rail works; provider refs
     * are observable on the intent and in the provider gateway's audit trail.
     */
    public PaymentIntent recordProviderHandoff(String provider, String providerRef, Instant at) {
        this.provider = blankToNull(provider);
        this.providerRef = blankToNull(providerRef);
        this.updatedAt = at;
        return this;
    }

    private void transition(PaymentState to, String reason, UUID entryId, Instant at) {
        Objects.requireNonNull(at, "transition instant is required");
        PaymentState from = state;
        if (!from.canTransitionTo(to)) {
            throw new PaymentStateException(id, from, to);
        }
        state = to;
        updatedAt = at;
        append(from, to, reason, entryId, at);
    }

    private void append(PaymentState from, PaymentState to, String reason, UUID entryId,
                        Instant at) {
        transitionSeq++;
        pendingTransitions.add(new StateTransition(id, transitionSeq, from, to, reason, entryId,
                at));
    }

    // ── invariants / guards ───────────────────────────────────────────────

    /** Whether the expiry deadline has passed while still PENDING_PROVIDER. */
    public boolean isExpiredAt(Instant now) {
        return state == PaymentState.PENDING_PROVIDER && !now.isBefore(expiresAt);
    }

    /** The amount a reversal can pull back: the capture, or zero. */
    public Money capturableAmount() {
        return state == PaymentState.SUCCEEDED ? amount : Money.zero(amount.currency());
    }

    // ── repository contract ───────────────────────────────────────────────

    /** Drains transitions recorded since the last save (repository calls). */
    public List<StateTransition> drainPendingTransitions() {
        List<StateTransition> drained = List.copyOf(pendingTransitions);
        pendingTransitions.clear();
        return drained;
    }

    public String id() {
        return id;
    }

    public UUID internalId() {
        return internalId;
    }

    public UUID principalId() {
        return principalId;
    }

    public String sourceWalletId() {
        return sourceWalletId;
    }

    public Destination destination() {
        return destination;
    }

    public Money amount() {
        return amount;
    }

    public Money fee() {
        return fee;
    }

    public Rail rail() {
        return rail;
    }

    public PaymentState state() {
        return state;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Map<String, String> metadata() {
        return new LinkedHashMap<>(metadata);
    }

    public String provider() {
        return provider;
    }

    public String providerRef() {
        return providerRef;
    }

    public String holdId() {
        return holdId;
    }

    public UUID holdEntryId() {
        return holdEntryId;
    }

    public UUID captureEntryId() {
        return captureEntryId;
    }

    public UUID releaseEntryId() {
        return releaseEntryId;
    }

    public UUID reversalEntryId() {
        return reversalEntryId;
    }

    public Money reversedAmount() {
        return reversedAmount;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long transitionSeq() {
        return transitionSeq;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static UUID coalesce(UUID first, UUID second) {
        return first != null ? first : second;
    }

    private static String requireSet(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for this transition");
        }
        return value;
    }

    private static UUID requireSet(UUID value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required for this transition");
        }
        return value;
    }

    private static Money requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("payment amount must be positive: " + amount);
        }
        return amount;
    }

    private static String requireId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "payment id must match " + ID_PATTERN.pattern() + ": " + id);
        }
        return id;
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        return key.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PaymentIntent intent)) {
            return false;
        }
        return id.equals(intent.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "PaymentIntent[" + id + " " + state.wireName() + " " + amount + " fee " + fee
                + " rail " + rail.wireName() + " → " + destination + "]";
    }
}
