package com.sharkpay.risk.domain;

import com.sharkpay.risk.domain.exceptions.IllegalCaseTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A compliance case (docs/PRD.md D8: case management, SAR-ready). Aggregate
 * root: the state machine and the transition log (actor per transition) live
 * here so every persistence adapter persists the same invariants.
 */
public final class Case {

    private final UUID id;
    private final String subjectPrincipalId;
    private final String reason;
    private final Instant createdAt;
    private final List<CaseTransition> transitions = new ArrayList<>();
    private CaseStatus status;
    private String assignedTo;
    private Instant updatedAt;

    private Case(UUID id, String subjectPrincipalId, String reason, CaseStatus status,
                 String assignedTo, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.subjectPrincipalId = requireText(subjectPrincipalId, "subjectPrincipalId");
        this.reason = requireText(reason, "reason");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.assignedTo = assignedTo;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /** Opens a new case in state OPEN. */
    public static Case open(UUID id, String subjectPrincipalId, String reason, Instant at) {
        return new Case(id, subjectPrincipalId, reason, CaseStatus.OPEN, null, at, at);
    }

    /** Rehydrates a persisted case with its full transition log. */
    public static Case restore(UUID id, String subjectPrincipalId, String reason, CaseStatus status,
                               String assignedTo, Instant createdAt, Instant updatedAt,
                               List<CaseTransition> transitions) {
        Case c = new Case(id, subjectPrincipalId, reason, status, assignedTo, createdAt, updatedAt);
        c.transitions.addAll(transitions);
        return c;
    }

    /**
     * Applies a state transition. Legal edges only
     * ({@link CaseStatus#canTransitionTo}); CLOSED is terminal. 4-eyes: the
     * acting operator id is recorded on the transition. Resolution is
     * required when closing and forbidden otherwise.
     */
    public void transitionTo(CaseStatus target, String actor, CaseResolution resolution, Instant at) {
        Objects.requireNonNull(target, "target status must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalCaseTransitionException(CaseIds.publicId(id), status, target);
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        if (target == CaseStatus.CLOSED && resolution == null) {
            throw new IllegalArgumentException("resolution is required when closing a case");
        }
        if (target != CaseStatus.CLOSED && resolution != null) {
            throw new IllegalArgumentException("resolution is only allowed when closing a case");
        }
        Objects.requireNonNull(at, "transition time must not be null");
        transitions.add(new CaseTransition(null, status, target, actor.trim(), resolution, at));
        status = target;
        updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    /** Externally visible case id ({@code case_<hex32>}). */
    public String publicId() {
        return CaseIds.publicId(id);
    }

    public String subjectPrincipalId() {
        return subjectPrincipalId;
    }

    public String reason() {
        return reason;
    }

    public CaseStatus status() {
        return status;
    }

    public String assignedTo() {
        return assignedTo;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Ordered, immutable transition log. */
    public List<CaseTransition> transitions() {
        return Collections.unmodifiableList(transitions);
    }

    private static String requireText(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return raw.trim();
    }
}
