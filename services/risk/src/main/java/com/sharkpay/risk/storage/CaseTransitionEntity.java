package com.sharkpay.risk.storage;

import com.sharkpay.risk.domain.CaseResolution;
import com.sharkpay.risk.domain.CaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** case_transitions row — the 4-eyes transition log (V1__risk_init.sql). */
@Entity
@Table(name = "case_transitions")
public class CaseTransitionEntity {

    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    @Column(name = "case_id", nullable = false)
    public UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false)
    public CaseStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    public CaseStatus toStatus;

    @Column(name = "actor", nullable = false)
    public String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution")
    public CaseResolution resolution;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    protected CaseTransitionEntity() {
        // JPA
    }

    public CaseTransitionEntity(UUID id, UUID caseId, CaseStatus fromStatus, CaseStatus toStatus,
                                String actor, CaseResolution resolution, Instant occurredAt) {
        this.id = id;
        this.caseId = caseId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.resolution = resolution;
        this.occurredAt = occurredAt;
    }
}
