package com.sharkpay.reconciliation.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A 4-eyes-controlled compensation for one break (RB-7 steps 2–5): operator
 * A (the requester) drafts the entry that makes both sides agree; operator
 * B (the approver — a <b>distinct person</b>, checked before execution)
 * reviews the exact legs and approves; only then does the entry execute
 * through the {@code LedgerPort} (POST to the ledger's internal
 * transactions API — never SQL, RB-7 step 4).
 *
 * <p>Lifecycle: {@code PROPOSED → EXECUTED}. Execution happens at most
 * once per entry (state gate in the use case + idempotency on the
 * compensation key at the ledger). The ledger journal entry id is recorded
 * on the entry, and the break is linked — the entry is the audit link
 * (RB-7 step 5). A ledger business rejection leaves the entry PROPOSED
 * (nothing was posted); the operators amend and propose a new entry.</p>
 *
 * <p><b>Compensation key</b> (RB-7 step 4: {@code ops:adj:<break_id>}):
 * the ledger transaction key of the posting — source {@code ops},
 * therefore the key starts with {@code ops:}. Repeated compensations for
 * the same break (a wrong first compensation is corrected by
 * <i>another</i> compensation, never a reversal-of-reversal) append a
 * sequence suffix: {@code ops:adj:<break_id>#2}, {@code #3}, …</p>
 */
public final class CompensationEntry {

    /** RB-7 compensation key prefix. */
    public static final String KEY_PREFIX = "ops:adj:";

    private final String id;
    private final String breakId;
    private final String provider;
    private final String compensationKey;
    private final String requester;
    private final String reason;
    private final List<CompensationLeg> legs;
    private final java.util.UUID reversesEntryId;
    private CompensationState state;
    private String approver;
    private java.util.UUID ledgerEntryId;
    private Instant executedAt;
    private boolean ledgerReplay;

    private CompensationEntry(String id, String breakId, String provider, String compensationKey,
                              String requester, String reason, List<CompensationLeg> legs,
                              java.util.UUID reversesEntryId, CompensationState state, String approver,
                              java.util.UUID ledgerEntryId, Instant executedAt, boolean ledgerReplay) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.breakId = Objects.requireNonNull(breakId, "breakId is required");
        this.provider = Objects.requireNonNull(provider, "provider is required");
        this.compensationKey = Objects.requireNonNull(compensationKey, "compensationKey is required");
        this.requester = requirePrincipal(requester, "requester");
        this.reason = requireReason(reason);
        this.legs = validateLegs(legs);
        this.reversesEntryId = reversesEntryId;
        this.state = Objects.requireNonNull(state, "state is required");
        this.approver = approver == null ? null : requirePrincipal(approver, "approver");
        this.ledgerEntryId = ledgerEntryId;
        this.executedAt = executedAt;
        this.ledgerReplay = ledgerReplay;
    }

    /**
     * Operator A drafts the compensation for {@code breakId}. {@code key}
     * is the RB-7 compensation key ({@code ops:adj:<breakId>[#n]}).
     */
    public static CompensationEntry propose(String id, String breakId, String provider, String key,
                                            String requester, String reason, List<CompensationLeg> legs,
                                            java.util.UUID reversesEntryId) {
        if (key == null || !key.startsWith(KEY_PREFIX) || key.length() < KEY_PREFIX.length() + 4) {
            throw new IllegalArgumentException("compensation key must be a ledger ops key like "
                    + KEY_PREFIX + "<break_id>: " + key);
        }
        if (key.length() > 128) {
            throw new IllegalArgumentException("compensation key must be at most 128 characters");
        }
        return new CompensationEntry(id, breakId, provider, key, requester, reason, legs,
                reversesEntryId, CompensationState.PROPOSED, null, null, null, false);
    }

    /** Rehydrates an entry from storage (all fields, no state guards). */
    public static CompensationEntry rehydrate(String id, String breakId, String provider, String key,
                                              String requester, String reason, List<CompensationLeg> legs,
                                              java.util.UUID reversesEntryId, CompensationState state,
                                              String approver, java.util.UUID ledgerEntryId,
                                              Instant executedAt, boolean ledgerReplay) {
        return new CompensationEntry(id, breakId, provider, key, requester, reason, legs,
                reversesEntryId, state, approver, ledgerEntryId, executedAt, ledgerReplay);
    }

    /**
     * Records operator B's approval and the execution outcome
     * (atomically: the approval is recorded before the ledger posting, the
     * entry id right after). 4-eyes is enforced by the use case
     * (approver ≠ requester) before this method runs.
     *
     * @param ledgerEntryId the journal entry id the ledger returned
     * @param replay        true when the ledger reported an idempotent
     *                      replay of the same compensation key (same entry
     *                      id — still exactly one posting in the ledger)
     */
    public void execute(String approver, java.util.UUID ledgerEntryId, boolean replay, Instant now) {
        if (state != CompensationState.PROPOSED) {
            throw new ReconciliationStateException(
                    "compensation " + id + " is already " + state.wireName()
                            + "; it can never execute twice");
        }
        this.approver = requirePrincipal(approver, "approver");
        this.ledgerEntryId = Objects.requireNonNull(ledgerEntryId, "ledgerEntryId is required");
        this.ledgerReplay = replay;
        this.state = CompensationState.EXECUTED;
        this.executedAt = Objects.requireNonNull(now, "now is required");
    }

    /** Structural ledger invariants checked before proposing (mirror of DATA-MODEL §4.2). */
    private static List<CompensationLeg> validateLegs(List<CompensationLeg> legs) {
        Objects.requireNonNull(legs, "legs is required");
        if (legs.size() < 2) {
            throw new IllegalArgumentException(
                    "a compensation entry needs at least 2 legs (balanced per currency), got "
                            + legs.size());
        }
        List<CompensationLeg> copy = List.copyOf(legs);
        Map<String, BigInteger> balanceByCurrency = new LinkedHashMap<>();
        for (CompensationLeg leg : copy) {
            String currency = leg.amount().currency();
            BigInteger signed = BigInteger.valueOf(leg.amount().amountMinor())
                    .multiply(BigInteger.valueOf(leg.direction() == PostingDirection.DEBIT ? 1 : -1));
            balanceByCurrency.merge(currency, signed, BigInteger::add);
        }
        for (Map.Entry<String, BigInteger> entry : balanceByCurrency.entrySet()) {
            if (entry.getValue().signum() != 0) {
                throw new IllegalArgumentException("compensation legs must balance per currency: "
                        + entry.getKey() + " is off by " + entry.getValue() + " minor units");
            }
        }
        return copy;
    }

    private static String requirePrincipal(String principal, String field) {
        Objects.requireNonNull(principal, field + " is required");
        if (principal.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (principal.length() > 128) {
            throw new IllegalArgumentException(field + " must be at most 128 characters");
        }
        return principal;
    }

    private static String requireReason(String reason) {
        Objects.requireNonNull(reason, "reason is required");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        // room for the automatic break reference the posting reason appends
        if (reason.length() > 400) {
            throw new IllegalArgumentException("reason must be at most 400 characters");
        }
        return reason;
    }

    public String id() {
        return id;
    }

    public String breakId() {
        return breakId;
    }

    public String provider() {
        return provider;
    }

    public String compensationKey() {
        return compensationKey;
    }

    public String requester() {
        return requester;
    }

    public String reason() {
        return reason;
    }

    public List<CompensationLeg> legs() {
        return legs;
    }

    public java.util.UUID reversesEntryId() {
        return reversesEntryId;
    }

    public CompensationState state() {
        return state;
    }

    public String approver() {
        return approver;
    }

    public java.util.UUID ledgerEntryId() {
        return ledgerEntryId;
    }

    public Instant executedAt() {
        return executedAt;
    }

    public boolean ledgerReplay() {
        return ledgerReplay;
    }

    /** Lifecycle: PROPOSED (drafted, awaiting 4-eyes) → EXECUTED (posted). */
    public enum CompensationState {

        PROPOSED("proposed"),
        EXECUTED("executed");

        private final String wireName;

        CompensationState(String wireName) {
            this.wireName = wireName;
        }

        /** The wire/API/DB name. */
        public String wireName() {
            return wireName;
        }

        /** Parses the wire name (storage/API); never guesses. */
        public static CompensationState fromWireName(String wireName) {
            for (CompensationState state : values()) {
                if (state.wireName.equals(wireName)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("unknown compensation state: " + wireName);
        }
    }
}
