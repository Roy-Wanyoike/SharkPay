package com.sharkpay.payouts.domain;

import com.sharkpay.money.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Payout aggregate: withdrawal from a SharkPay wallet to an external rail
 * destination (mpesa / bank / on-chain), lifecycle per
 * docs/STATE-MACHINES.md §2:
 *
 * <pre>
 * CREATED → PENDING_RISK → PROCESSING → SENT → SUCCEEDED
 *                 │            │           │
 *                 ▼            ▼           ▼
 *             BLOCKED       FAILED     RETURNED
 * </pre>
 *
 * plus {@code CANCELLED} from CREATED/PENDING_RISK (user cancel, payouts.yaml)
 * and from PROCESSING (system TTL expiry once the provider confirms
 * cancellation). Rail states map to the operational flow as:
 * scheduled = PENDING_RISK ({@code executeAfter}), submitted = PROCESSING,
 * pending_provider = SENT, settled = SUCCEEDED.
 *
 * <p>Money state alignment (STATE-MACHINES §7.4): the ledger hold entry
 * exists iff the payout was accepted; the settle entry exists iff
 * SUCCEEDED; the compensation entry exists iff RETURNED; the release
 * reversal exists iff the payout failed/was cancelled/blocked after the
 * hold. Entry ids are stamped by the use-cases right after ledger
 * confirmation; the aggregate refuses terminal transitions without them.</p>
 *
 * <p>Retry bookkeeping: {@code attempts} counts failed provider
 * submissions; {@code nextAttemptAt} is the next backoff release time
 * (bounded exponential + jitter, see {@link BackoffPolicy}). Submission
 * retries never mutate recorded state history.</p>
 */
public final class Payout {

    /** Public payout id pattern (contracts/openapi/v1/payouts.yaml). */
    public static final Pattern ID_PATTERN = Pattern.compile("^pot_[0-9A-Za-z]{20,}$");

    private final String id;
    private final UUID internalRef;
    private final String sourceWalletId;
    private final UUID walletLedgerAccountId;
    private final Money amount;
    private final Money fee;
    private final Money nonRefundableFee;
    private final Rail rail;
    private final Destination destination;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final Map<String, String> metadata;
    private final List<StateTransition> transitions = new ArrayList<>();
    private int persistedTransitions;
    private PayoutState state;
    private String providerRef;
    private String failureReason;
    private String returnReason;
    private int attempts;
    private Instant executeAfter;
    private Instant nextAttemptAt;
    private UUID holdEntryId;
    private UUID settleEntryId;
    private UUID returnEntryId;
    private Instant updatedAt;

    public Payout(String id, UUID internalRef, String sourceWalletId, Money amount, Money fee,
                  Money nonRefundableFee, Rail rail, Destination destination, PayoutState state,
                  String providerRef, String failureReason, String returnReason, int attempts,
                  Instant executeAfter, Instant nextAttemptAt, Instant expiresAt,
                  UUID holdEntryId, UUID settleEntryId, UUID returnEntryId,
                  Map<String, String> metadata, Instant createdAt, Instant updatedAt) {
        this(id, internalRef, sourceWalletId, null, amount, fee, nonRefundableFee, rail, destination,
                state, providerRef, failureReason, returnReason, attempts, executeAfter,
                nextAttemptAt, expiresAt, holdEntryId, settleEntryId, returnEntryId, metadata,
                createdAt, updatedAt, List.of());
    }

    /** Full rehydration constructor (repository load path). */
    public Payout(String id, UUID internalRef, String sourceWalletId, Money amount, Money fee,
                  Money nonRefundableFee, Rail rail, Destination destination, PayoutState state,
                  String providerRef, String failureReason, String returnReason, int attempts,
                  Instant executeAfter, Instant nextAttemptAt, Instant expiresAt,
                  UUID holdEntryId, UUID settleEntryId, UUID returnEntryId,
                  Map<String, String> metadata, Instant createdAt, Instant updatedAt,
                  List<StateTransition> history) {
        this(id, internalRef, sourceWalletId, null, amount, fee, nonRefundableFee, rail,
                destination, state, providerRef, failureReason, returnReason, attempts, executeAfter,
                nextAttemptAt, expiresAt, holdEntryId, settleEntryId, returnEntryId, metadata,
                createdAt, updatedAt, history);
    }

    /** Canonical constructor with the wallet's ledger account. */
    public Payout(String id, UUID internalRef, String sourceWalletId, UUID walletLedgerAccountId,
                  Money amount, Money fee, Money nonRefundableFee, Rail rail,
                  Destination destination, PayoutState state, String providerRef,
                  String failureReason, String returnReason, int attempts, Instant executeAfter,
                  Instant nextAttemptAt, Instant expiresAt, UUID holdEntryId, UUID settleEntryId,
                  UUID returnEntryId, Map<String, String> metadata, Instant createdAt,
                  Instant updatedAt, List<StateTransition> history) {
        this.id = requireId(id);
        this.internalRef = Objects.requireNonNull(internalRef, "internalRef is required");
        this.sourceWalletId = requireWalletId(sourceWalletId);
        this.walletLedgerAccountId = Objects.requireNonNull(walletLedgerAccountId,
                "walletLedgerAccountId is required (resolved from the wallet snapshot at creation)");
        this.amount = Objects.requireNonNull(amount, "amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("payout amount must be positive");
        }
        this.fee = Objects.requireNonNull(fee, "fee is required");
        this.nonRefundableFee = Objects.requireNonNull(nonRefundableFee, "nonRefundableFee is required");
        requireSameCurrency(amount, fee);
        requireSameCurrency(amount, nonRefundableFee);
        if (nonRefundableFee.isNegative() || nonRefundableFee.amountMinor() > fee.amountMinor()) {
            throw new IllegalArgumentException(
                    "non-refundable fee must be between 0 and the total fee");
        }
        this.rail = Objects.requireNonNull(rail, "rail is required");
        this.destination = Objects.requireNonNull(destination, "destination is required");
        if (destination.rail() != rail) {
            throw new UnsupportedDestinationException("destination type "
                    + destination.type() + " is not compatible with rail " + rail.wireName());
        }
        if (!rail.supportsCurrency(amount.currency())) {
            throw new UnsupportedDestinationException("rail " + rail.wireName()
                    + " does not support currency " + amount.currency());
        }
        this.state = Objects.requireNonNull(state, "state is required");
        this.providerRef = providerRef;
        this.failureReason = failureReason;
        this.returnReason = returnReason;
        this.attempts = Math.max(0, attempts);
        this.executeAfter = executeAfter;
        this.nextAttemptAt = nextAttemptAt;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
        this.holdEntryId = holdEntryId;
        this.settleEntryId = settleEntryId;
        this.returnEntryId = returnEntryId;
        this.metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(
                new LinkedHashMap<>(metadata));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        this.transitions.addAll(history);
        this.persistedTransitions = this.transitions.size();
    }

    /** Creates a fresh CREATED payout awaiting risk acceptance. */
    public static Payout newPayout(String id, UUID internalRef, String sourceWalletId,
                                   UUID walletLedgerAccountId, Money amount, Money fee,
                                   Money nonRefundableFee, Rail rail, Destination destination,
                                   Map<String, String> metadata, Instant executeAfter,
                                   Instant expiresAt, Instant at) {
        return new Payout(id, internalRef, sourceWalletId, walletLedgerAccountId, amount, fee,
                nonRefundableFee, rail, destination, PayoutState.CREATED, null, null, null, 0,
                executeAfter, null, expiresAt, null, null, null, metadata, at, at, List.of());
    }

    /**
     * Accepts the payout for the provider pipeline: CREATED → PENDING_RISK
     * with the scheduled release time ({@code executeAfter}) and the hold
     * entry the use-case just confirmed.
     */
    public void accept(Instant holdConfirmedExecuteAfter, UUID holdEntryId, Instant at) {
        Objects.requireNonNull(holdEntryId, "holdEntryId is required");
        Objects.requireNonNull(holdConfirmedExecuteAfter, "executeAfter is required");
        requireState(PayoutState.CREATED, "accept");
        this.executeAfter = holdConfirmedExecuteAfter;
        this.holdEntryId = holdEntryId;
        this.state = PayoutState.PENDING_RISK;
        this.updatedAt = at;
        transitions.add(new StateTransition(PayoutState.CREATED, PayoutState.PENDING_RISK,
                "risk_pass", "system", "held " + (amount.amountMinor() + fee.amountMinor())
                        + " " + amount.currency(), at));
    }

    /** Risk deny: CREATED/PENDING_RISK → BLOCKED (no money moved onwards). */
    public void riskDeny(String reason, Instant at) {
        String auditReason = requireReason(reason);
        PayoutState from = requireAnyOf(PayoutState.BLOCKED, PayoutState.CREATED,
                PayoutState.PENDING_RISK);
        this.state = PayoutState.BLOCKED;
        this.updatedAt = at;
        transitions.add(new StateTransition(from, PayoutState.BLOCKED, "risk", "risk",
                auditReason, at));
    }

    /**
     * The scheduler released the payout and the provider accepted the
     * submission: PENDING_RISK → PROCESSING with the provider-side
     * reference.
     */
    public void markSubmitted(String providerRefValue, Instant at) {
        String ref = requireRef(providerRefValue);
        requireState(PayoutState.PENDING_RISK, "submit");
        this.providerRef = ref;
        this.nextAttemptAt = null;
        this.state = PayoutState.PROCESSING;
        this.updatedAt = at;
        transitions.add(new StateTransition(PayoutState.PENDING_RISK, PayoutState.PROCESSING,
                "scheduler", "scheduler", "submitted to " + destination.describe(), at));
    }


    /**
     * A submission attempt failed: records the attempt and the next retry
     * time (bounded backoff). State stays PENDING_RISK — this is retry
     * bookkeeping, not a lifecycle transition, so no audit row is written.
     */
    public void recordSubmitFailure(Instant nextAttemptAtValue, Instant at) {
        Objects.requireNonNull(nextAttemptAtValue, "nextAttemptAt is required");
        requireState(PayoutState.PENDING_RISK, "record submit failure");
        this.attempts++;
        this.nextAttemptAt = nextAttemptAtValue;
        this.updatedAt = at;
    }

    /** The rail accepted the transfer: PROCESSING → SENT. */
    public void markSent(Instant at) {
        requireState(PayoutState.PROCESSING, "mark sent");
        requireRef(providerRef);
        this.state = PayoutState.SENT;
        this.updatedAt = at;
        transitions.add(new StateTransition(PayoutState.PROCESSING, PayoutState.SENT,
                "provider_callback", "provider", null, at));
    }

    /** Settlement confirmed at the destination: SENT → SUCCEEDED. */
    public void markSucceeded(UUID settleEntryIdValue, Instant at) {
        Objects.requireNonNull(settleEntryIdValue, "settleEntryId is required");
        requireState(PayoutState.SENT, "settle");
        this.settleEntryId = settleEntryIdValue;
        this.state = PayoutState.SUCCEEDED;
        this.updatedAt = at;
        transitions.add(new StateTransition(PayoutState.SENT, PayoutState.SUCCEEDED,
                "provider_callback", "provider", null, at));
    }

    /**
     * Terminal failure (rail failure confirmed, retries exhausted, or early
     * rejection): CREATED/PENDING_RISK/PROCESSING → FAILED. The use-case
     * posts the hold release reversal before calling this.
     */
    public void markFailed(String reason, Instant at) {
        String auditReason = requireReason(reason);
        PayoutState from = requireAnyOf(PayoutState.FAILED, PayoutState.CREATED,
                PayoutState.PENDING_RISK, PayoutState.PROCESSING);
        this.failureReason = auditReason;
        this.state = PayoutState.FAILED;
        this.updatedAt = at;
        transitions.add(new StateTransition(from, PayoutState.FAILED,
                "provider_callback", "provider", auditReason, at));
    }

    /**
     * The rail returned the funds: SENT/SUCCEEDED → RETURNED. The
     * compensation entry id must already be confirmed by the ledger —
     * returns never re-credit without it.
     */
    public void markReturned(String reason, UUID returnEntryIdValue, Instant at) {
        String auditReason = requireReason(reason);
        Objects.requireNonNull(returnEntryIdValue, "returnEntryId is required");
        PayoutState from = requireAnyOf(PayoutState.RETURNED, PayoutState.SENT,
                PayoutState.SUCCEEDED);
        this.returnReason = auditReason;
        this.returnEntryId = returnEntryIdValue;
        this.state = PayoutState.RETURNED;
        this.updatedAt = at;
        transitions.add(new StateTransition(from, PayoutState.RETURNED,
                "provider_callback", "provider", auditReason, at));
    }

    /**
     * Cancellation. User-initiated cancellation is legal only before the
     * provider accepts the payout (CREATED/PENDING_RISK — payouts.yaml);
     * the TTL sweeper may also cancel a PROCESSING payout after the
     * provider confirmed cancellation (system actor, expiry trigger).
     */
    public void cancel(String reason, Instant at, boolean systemActor) {
        String auditReason = requireReason(reason);
        PayoutState from = systemActor
                ? requireAnyOf(PayoutState.CANCELLED, PayoutState.CREATED,
                        PayoutState.PENDING_RISK, PayoutState.PROCESSING)
                : requireAnyOf(PayoutState.CANCELLED, PayoutState.CREATED,
                        PayoutState.PENDING_RISK);
        this.state = PayoutState.CANCELLED;
        this.updatedAt = at;
        transitions.add(new StateTransition(from, PayoutState.CANCELLED,
                systemActor ? "expiry" : "api", systemActor ? "system" : "principal",
                auditReason, at));
    }

    /** True when the release scheduler may submit this payout at {@code now}. */
    public boolean dueForRelease(Instant now) {
        if (state != PayoutState.PENDING_RISK) {
            return false;
        }
        if (executeAfter != null && executeAfter.isAfter(now)) {
            return false;
        }
        return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
    }

    /** True when the TTL sweeper should cancel this payout at {@code now}. */
    public boolean expired(Instant now) {
        return (state == PayoutState.PENDING_RISK || state == PayoutState.PROCESSING)
                && expiresAt.isBefore(now);
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    /** True once the ledger hold has been posted (PENDING_RISK onwards). */
    public boolean isHeld() {
        return holdEntryId != null && state != PayoutState.CREATED;
    }

    private void requireState(PayoutState expected, String attempted) {
        if (state != expected) {
            throw new PayoutStateException(id, state, expected);
        }
    }

    /** Guards a multi-source transition, returning the current state. */
    private PayoutState requireAnyOf(PayoutState target, PayoutState... allowed) {
        for (PayoutState candidate : allowed) {
            if (state == candidate) {
                return state;
            }
        }
        // the 409 body names the attempted target, never a legal source
        // state — clients and ops must see what was refused
        throw new PayoutStateException(id, state, target);
    }

    private static void requireSameCurrency(Money first, Money second) {
        if (!first.currency().equals(second.currency())) {
            throw new com.sharkpay.money.CurrencyMismatchException(first.currency(),
                    second.currency());
        }
    }

    private static String requireId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "payout id must match " + ID_PATTERN.pattern() + ": " + id);
        }
        return id;
    }

    private static String requireWalletId(String walletId) {
        if (walletId == null || !Wallet.ID_PATTERN.matcher(walletId).matches()) {
            throw new IllegalArgumentException(
                    "source wallet must match " + Wallet.ID_PATTERN.pattern() + ": " + walletId);
        }
        return walletId;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 512) {
            throw new IllegalArgumentException("reason must be at most 512 characters");
        }
        return trimmed;
    }

    private static String requireRef(String providerRef) {
        if (providerRef == null || providerRef.isBlank()) {
            throw new IllegalArgumentException("provider reference must not be blank");
        }
        String trimmed = providerRef.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("provider reference must be at most 128 characters");
        }
        return trimmed;
    }

    public String id() {
        return id;
    }

    public UUID internalRef() {
        return internalRef;
    }

    /** The principal wallet's ledger account (legs key on it). */
    public UUID walletLedgerAccountId() {
        return walletLedgerAccountId;
    }

    public String sourceWalletId() {
        return sourceWalletId;
    }

    public Money amount() {
        return amount;
    }

    public Money fee() {
        return fee;
    }

    /** Portion of the fee retained by the rail on a RETURNED payout. */
    public Money nonRefundableFee() {
        return nonRefundableFee;
    }

    public Rail rail() {
        return rail;
    }

    public Destination destination() {
        return destination;
    }

    public PayoutState state() {
        return state;
    }

    public String providerRef() {
        return providerRef;
    }

    public String failureReason() {
        return failureReason;
    }

    public String returnReason() {
        return returnReason;
    }

    public int attempts() {
        return attempts;
    }

    public Instant executeAfter() {
        return executeAfter;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public UUID holdEntryId() {
        return holdEntryId;
    }

    public UUID settleEntryId() {
        return settleEntryId;
    }

    public UUID returnEntryId() {
        return returnEntryId;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** All recorded transitions, oldest first (append-only audit trail). */
    public List<StateTransition> transitions() {
        return List.copyOf(transitions);
    }

    /** Transitions a repository has not persisted yet, oldest first. */
    public List<StateTransition> pendingTransitions() {
        return List.copyOf(transitions.subList(persistedTransitions, transitions.size()));
    }

    /** Called by the repository after persisting {@link #pendingTransitions()}. */
    public void markTransitionsPersisted() {
        this.persistedTransitions = transitions.size();
    }
}
