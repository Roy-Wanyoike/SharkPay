package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * One reconciliation break — a classified discrepancy with an auditable
 * lifecycle (OPEN → INVESTIGATING → RESOLVED | COMPENSATED | WAIVED) and an
 * aging bucket recomputed from {@code detectedAt} (RB-7).
 *
 * <p>Resolution discipline (RB-7): every transition records the acting
 * principal and a note (the hypothesis written in the ticket); the
 * COMPENSATED transition is driven exclusively by compensation execution —
 * it is the only money-moving outcome and it requires 4-eyes at the
 * {@link CompensationEntry} level; terminal states never re-open (a wrong
 * resolution is corrected by another compensation entry, DATA-MODEL
 * §4.5).</p>
 */
public final class ReconBreak {

    private final String id;
    private final String runId;
    private final String provider;
    private final BreakType breakType;
    private final String providerRef;
    private final String internalRef;
    private final Money providerAmount;
    private final Money internalAmount;
    private final Money providerFee;
    private final Money internalFee;
    private final String providerStatus;
    private final String internalStatus;
    private final Instant detectedAt;
    private BreakState state;
    private AgingBucket bucket;
    private String note;
    private String lastActor;
    private Instant lastTransitionAt;
    private String compensationId;
    private Instant resolvedAt;
    private Instant escalatedAt;

    private ReconBreak(Builder builder) {
        this.id = builder.id;
        this.runId = builder.runId;
        this.provider = builder.provider;
        this.breakType = builder.breakType;
        this.providerRef = builder.providerRef;
        this.internalRef = builder.internalRef;
        this.providerAmount = builder.providerAmount;
        this.internalAmount = builder.internalAmount;
        this.providerFee = builder.providerFee;
        this.internalFee = builder.internalFee;
        this.providerStatus = builder.providerStatus;
        this.internalStatus = builder.internalStatus;
        this.detectedAt = builder.detectedAt;
        this.state = builder.state;
        this.bucket = builder.bucket;
        this.note = builder.note;
        this.lastActor = builder.lastActor;
        this.lastTransitionAt = builder.lastTransitionAt;
        this.compensationId = builder.compensationId;
        this.resolvedAt = builder.resolvedAt;
        this.escalatedAt = builder.escalatedAt;
    }

    /**
     * Records a freshly detected break: OPEN, FRESH, detected at
     * {@code now}.
     */
    public static ReconBreak detect(String id, String runId, String provider, DetectedBreak detected,
                                    Instant now) {
        Objects.requireNonNull(detected, "detected is required");
        Objects.requireNonNull(now, "now is required");
        return new Builder(id, runId, provider, detected.breakType())
                .providerRef(detected.providerRef())
                .internalRef(detected.internalRef())
                .providerAmount(detected.providerAmount())
                .internalAmount(detected.internalAmount())
                .providerFee(detected.providerFee())
                .internalFee(detected.internalFee())
                .providerStatus(detected.providerStatus())
                .internalStatus(detected.internalStatus())
                .detectedAt(now)
                .state(BreakState.OPEN)
                .bucket(AgingBucket.FRESH)
                .lastTransitionAt(now)
                .build();
    }

    /** Rehydrates a break from storage (all fields, no state guards). */
    public static ReconBreak rehydrate(Builder builder) {
        return builder.build();
    }

    /** OPEN → INVESTIGATING: an operator picked the break up (RB-7 step 1). */
    public void startInvestigation(String principal, String note, Instant now) {
        Objects.requireNonNull(principal, "principal is required");
        requireNote(note);
        requireTransition(BreakState.OPEN, BreakState.INVESTIGATING);
        this.state = BreakState.INVESTIGATING;
        this.note = note.trim();
        this.lastActor = principal;
        this.lastTransitionAt = Objects.requireNonNull(now, "now is required");
    }

    /** INVESTIGATING → RESOLVED: closed without any money movement. */
    public void resolve(String principal, String note, Instant now) {
        Objects.requireNonNull(principal, "principal is required");
        requireNote(note);
        requireTransition(BreakState.INVESTIGATING, BreakState.RESOLVED);
        this.state = BreakState.RESOLVED;
        this.note = note.trim();
        this.lastActor = principal;
        this.lastTransitionAt = Objects.requireNonNull(now, "now is required");
        this.resolvedAt = now;
    }

    /** INVESTIGATING → WAIVED: documented decision not to act. */
    public void waive(String principal, String note, Instant now) {
        Objects.requireNonNull(principal, "principal is required");
        requireNote(note);
        requireTransition(BreakState.INVESTIGATING, BreakState.WAIVED);
        this.state = BreakState.WAIVED;
        this.note = note.trim();
        this.lastActor = principal;
        this.lastTransitionAt = Objects.requireNonNull(now, "now is required");
        this.resolvedAt = now;
    }

    /**
     * OPEN|INVESTIGATING → COMPENSATED — driven exclusively by a
     * successfully executed compensation entry (the 4-eyes-controlled
     * posting). Links the compensation id as the audit link (RB-7 step 5).
     */
    public void markCompensated(String compensationId, Instant now) {
        if (compensationId == null || compensationId.isBlank()) {
            throw new IllegalArgumentException("compensationId is required");
        }
        if (state.isTerminal()) {
            throw new ReconciliationStateException(
                    "break " + id + " is already " + state.wireName()
                            + "; a terminal break can never be compensated (correct with a new "
                            + "compensation entry instead)");
        }
        this.state = BreakState.COMPENSATED;
        this.compensationId = compensationId;
        this.resolvedAt = Objects.requireNonNull(now, "now is required");
        this.lastTransitionAt = now;
    }

    /**
     * Advances the persisted aging bucket (sweeper only); returns true when
     * the bucket advanced — the caller publishes the RB-7 escalation event
     * exactly once per transition.
     */
    public boolean advanceBucket(AgingBucket liveBucket, Instant now) {
        Objects.requireNonNull(liveBucket, "liveBucket is required");
        Objects.requireNonNull(now, "now is required");
        if (!state.isActive()) {
            throw new ReconciliationStateException(
                    "break " + id + " is terminal (" + state.wireName()
                            + "); aging stops at resolution");
        }
        if (liveBucket.ordinal() <= bucket.ordinal()) {
            return false;
        }
        this.bucket = liveBucket;
        this.escalatedAt = now;
        return true;
    }

    private void requireTransition(BreakState expectedFrom, BreakState to) {
        if (state != expectedFrom) {
            throw new ReconciliationStateException(
                    "break " + id + " is " + state.wireName() + "; legal transition to "
                            + to.wireName() + " requires " + expectedFrom.wireName());
        }
    }

    private static void requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("a break transition requires a note (RB-7: the "
                    + "hypothesis is written in the ticket)");
        }
        if (note.length() > 500) {
            throw new IllegalArgumentException("note must be at most 500 characters");
        }
    }

    public String id() {
        return id;
    }

    public String runId() {
        return runId;
    }

    public String provider() {
        return provider;
    }

    public BreakType breakType() {
        return breakType;
    }

    public String providerRef() {
        return providerRef;
    }

    public String internalRef() {
        return internalRef;
    }

    public Money providerAmount() {
        return providerAmount;
    }

    public Money internalAmount() {
        return internalAmount;
    }

    public Money providerFee() {
        return providerFee;
    }

    public Money internalFee() {
        return internalFee;
    }

    public String providerStatus() {
        return providerStatus;
    }

    public String internalStatus() {
        return internalStatus;
    }

    public Instant detectedAt() {
        return detectedAt;
    }

    public BreakState state() {
        return state;
    }

    /** The persisted bucket (updated by the sweeper; reads recompute live). */
    public AgingBucket bucket() {
        return bucket;
    }

    public String note() {
        return note;
    }

    public String lastActor() {
        return lastActor;
    }

    public Instant lastTransitionAt() {
        return lastTransitionAt;
    }

    public String compensationId() {
        return compensationId;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public Instant escalatedAt() {
        return escalatedAt;
    }

    /**
     * Fluent builder used by {@link #detect} (freshly detected) and
     * {@link #rehydrate} (storage mapping). All fields are settable; the
     * required identity fields are constructor arguments.
     */
    public static final class Builder {

        private final String id;
        private final String runId;
        private final String provider;
        private final BreakType breakType;
        private String providerRef;
        private String internalRef;
        private Money providerAmount;
        private Money internalAmount;
        private Money providerFee;
        private Money internalFee;
        private String providerStatus;
        private String internalStatus;
        private Instant detectedAt;
        private BreakState state;
        private AgingBucket bucket;
        private String note;
        private String lastActor;
        private Instant lastTransitionAt;
        private String compensationId;
        private Instant resolvedAt;
        private Instant escalatedAt;

        public Builder(String id, String runId, String provider, BreakType breakType) {
            this.id = Objects.requireNonNull(id, "id is required");
            this.runId = Objects.requireNonNull(runId, "runId is required");
            this.provider = Objects.requireNonNull(provider, "provider is required");
            if (id.isBlank() || runId.isBlank() || provider.isBlank()) {
                throw new IllegalArgumentException("id, runId and provider must not be blank");
            }
            this.breakType = Objects.requireNonNull(breakType, "breakType is required");
        }

        public Builder providerRef(String providerRef) {
            this.providerRef = providerRef;
            return this;
        }

        public Builder internalRef(String internalRef) {
            this.internalRef = internalRef;
            return this;
        }

        public Builder providerAmount(Money providerAmount) {
            this.providerAmount = providerAmount;
            return this;
        }

        public Builder internalAmount(Money internalAmount) {
            this.internalAmount = internalAmount;
            return this;
        }

        public Builder providerFee(Money providerFee) {
            this.providerFee = providerFee;
            return this;
        }

        public Builder internalFee(Money internalFee) {
            this.internalFee = internalFee;
            return this;
        }

        public Builder providerStatus(String providerStatus) {
            this.providerStatus = providerStatus;
            return this;
        }

        public Builder internalStatus(String internalStatus) {
            this.internalStatus = internalStatus;
            return this;
        }

        public Builder detectedAt(Instant detectedAt) {
            this.detectedAt = detectedAt;
            return this;
        }

        public Builder state(BreakState state) {
            this.state = state;
            return this;
        }

        public Builder bucket(AgingBucket bucket) {
            this.bucket = bucket;
            return this;
        }

        public Builder note(String note) {
            this.note = note;
            return this;
        }

        public Builder lastActor(String lastActor) {
            this.lastActor = lastActor;
            return this;
        }

        public Builder lastTransitionAt(Instant lastTransitionAt) {
            this.lastTransitionAt = lastTransitionAt;
            return this;
        }

        public Builder compensationId(String compensationId) {
            this.compensationId = compensationId;
            return this;
        }

        public Builder resolvedAt(Instant resolvedAt) {
            this.resolvedAt = resolvedAt;
            return this;
        }

        public Builder escalatedAt(Instant escalatedAt) {
            this.escalatedAt = escalatedAt;
            return this;
        }

        public ReconBreak build() {
            if (detectedAt == null) {
                throw new IllegalArgumentException("detectedAt is required");
            }
            if (state == null) {
                throw new IllegalArgumentException("break state is required");
            }
            if (bucket == null) {
                throw new IllegalArgumentException("aging bucket is required");
            }
            return new ReconBreak(this);
        }
    }
}
