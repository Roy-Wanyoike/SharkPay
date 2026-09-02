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
 * Internal wallet-to-wallet transfer aggregate (docs/STATE-MACHINES.md §3).
 * The whole money movement is ONE atomic ledger journal entry containing
 * both legs (debit source wallet, credit destination wallet): the transfer
 * reaches {@code SUCCEEDED} only on ledger confirmation and {@code FAILED}
 * only on a ledger rejection — never partially posted.
 *
 * <p>Every state change appends a {@link StateTransition} audit row
 * (docs/DATA-MODEL.md §1: "every mutable state change also writes a
 * transition/audit row"); repositories persist them to
 * {@code transfer_state_transitions}.</p>
 */
public final class Transfer {

    /** Public transfer id pattern (contracts/openapi/v1/transfers.yaml). */
    public static final Pattern ID_PATTERN = Pattern.compile("^trf_[0-9A-Za-z]{20,}$");

    private final String id;
    private final UUID internalRef;
    private final String sourceWalletId;
    private final String destinationWalletId;
    private final Money amount;
    private final Money fee;
    private final Instant createdAt;
    private final Map<String, String> metadata;
    private final List<StateTransition> transitions = new ArrayList<>();
    private int persistedTransitions;
    private TransferState state;
    private UUID entryId;
    private String failureReason;
    private Instant updatedAt;

    public Transfer(String id, UUID internalRef, String sourceWalletId, String destinationWalletId,
                    Money amount, Money fee, TransferState state, UUID entryId, String failureReason,
                    Map<String, String> metadata, Instant createdAt, Instant updatedAt) {
        this(id, internalRef, sourceWalletId, destinationWalletId, amount, fee, state, entryId,
                failureReason, metadata, createdAt, updatedAt, List.of());
    }

    /** Full rehydration constructor (repository load path). */
    public Transfer(String id, UUID internalRef, String sourceWalletId, String destinationWalletId,
                    Money amount, Money fee, TransferState state, UUID entryId, String failureReason,
                    Map<String, String> metadata, Instant createdAt, Instant updatedAt,
                    List<StateTransition> history) {
        this.id = requireId(id);
        this.internalRef = Objects.requireNonNull(internalRef, "internalRef is required");
        this.sourceWalletId = requireWalletId(sourceWalletId, "sourceWalletId");
        this.destinationWalletId = requireWalletId(destinationWalletId, "destinationWalletId");
        if (this.sourceWalletId.equals(this.destinationWalletId)) {
            throw new SameWalletException(this.sourceWalletId);
        }
        this.amount = Objects.requireNonNull(amount, "amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }
        this.fee = Objects.requireNonNull(fee, "fee is required");
        if (fee.amountMinor() < 0) {
            throw new IllegalArgumentException("transfer fee must be non-negative: " + fee);
        }
        requireSameCurrency(fee);
        this.state = Objects.requireNonNull(state, "state is required");
        this.entryId = entryId;
        this.failureReason = failureReason;
        this.metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(
                new LinkedHashMap<>(metadata));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        this.transitions.addAll(history);
        this.persistedTransitions = this.transitions.size();
    }

    /**
     * Creates a fresh transfer awaiting its single atomic ledger posting.
     * Fee is zero at V1 (internal transfers are free — transfers.yaml).
     */
    public static Transfer instantiate(String id, UUID internalRef, String sourceWalletId,
                                        String destinationWalletId, Money amount,
                                        Map<String, String> metadata, Instant at) {
        return new Transfer(id, internalRef, sourceWalletId, destinationWalletId, amount,
                Money.zero(amount.currency()), TransferState.CREATED, null, null, metadata, at, at);
    }

    /**
     * Ledger confirmed the atomic 2-leg posting: CREATED → SUCCEEDED, the
     * journal entry id becomes the transfer's {@code entry_id}.
     */
    public void markSucceeded(UUID ledgerEntryId, Instant at) {
        Objects.requireNonNull(ledgerEntryId, "ledgerEntryId is required");
        requireState(TransferState.CREATED, "settle");
        this.entryId = ledgerEntryId;
        this.state = TransferState.SUCCEEDED;
        this.updatedAt = at;
        transitions.add(new StateTransition(TransferState.CREATED, TransferState.SUCCEEDED,
                "ledger_confirmation", "system", null, at));
    }

    /**
     * Ledger rejected the posting (e.g. the authoritative balance check): the
     * transfer terminates FAILED without any leg having landed.
     */
    public void markFailed(String reason, Instant at) {
        String auditReason = requireReason(reason);
        requireState(TransferState.CREATED, "fail");
        this.failureReason = auditReason;
        this.state = TransferState.FAILED;
        this.updatedAt = at;
        transitions.add(new StateTransition(TransferState.CREATED, TransferState.FAILED,
                "ledger_rejection", "system", auditReason, at));
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    private void requireState(TransferState expected, String attempted) {
        if (state != expected) {
            throw new TransferStateException(id, state, expected);
        }
    }

    private void requireSameCurrency(Money other) {
        if (!amount.currency().equals(other.currency())) {
            throw new com.sharkpay.money.CurrencyMismatchException(amount.currency(),
                    other.currency());
        }
    }

    private static String requireId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "transfer id must match " + ID_PATTERN.pattern() + ": " + id);
        }
        return id;
    }

    private static String requireWalletId(String walletId, String field) {
        if (walletId == null || !Wallet.ID_PATTERN.matcher(walletId).matches()) {
            throw new IllegalArgumentException(
                    field + " must match " + Wallet.ID_PATTERN.pattern() + ": " + walletId);
        }
        return walletId;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("failure reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 512) {
            throw new IllegalArgumentException("failure reason must be at most 512 characters");
        }
        return trimmed;
    }

    public String id() {
        return id;
    }

    public UUID internalRef() {
        return internalRef;
    }

    public String sourceWalletId() {
        return sourceWalletId;
    }

    public String destinationWalletId() {
        return destinationWalletId;
    }

    public Money amount() {
        return amount;
    }

    public Money fee() {
        return fee;
    }

    public TransferState state() {
        return state;
    }

    public UUID entryId() {
        return entryId;
    }

    public String failureReason() {
        return failureReason;
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
