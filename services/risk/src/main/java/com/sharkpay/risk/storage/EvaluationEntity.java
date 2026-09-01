package com.sharkpay.risk.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * evaluations row (see V1__risk_init.sql). {@code id} is the caller-supplied
 * evaluation id (idempotency key). Field access on purpose: the entity is a
 * persistence detail, mappers in this package read/write it directly.
 */
@Entity
@Table(name = "evaluations")
public class EvaluationEntity {

    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    @Column(name = "subject_principal_id", nullable = false)
    public String subjectPrincipalId;

    @Column(name = "decision", nullable = false)
    public String decision;

    /** jsonb — serialized ordered reason list. */
    @Column(name = "reasons", nullable = false, columnDefinition = "jsonb")
    public String reasons;

    /** jsonb — the canonical request (idempotency conflict detection). */
    @Column(name = "request", nullable = false, columnDefinition = "jsonb")
    public String request;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    protected EvaluationEntity() {
        // JPA
    }

    public EvaluationEntity(UUID id, String subjectPrincipalId, String decision, String reasons,
                            String request, Instant createdAt) {
        this.id = id;
        this.subjectPrincipalId = subjectPrincipalId;
        this.decision = decision;
        this.reasons = reasons;
        this.request = request;
        this.createdAt = createdAt;
    }
}
