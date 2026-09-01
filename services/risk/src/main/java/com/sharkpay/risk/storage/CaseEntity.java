package com.sharkpay.risk.storage;

import com.sharkpay.risk.domain.CaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** cases row (V1__risk_init.sql). Internal UUID key; public id is case_&lt;hex32&gt;. */
@Entity
@Table(name = "cases")
public class CaseEntity {

    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    @Column(name = "subject_principal_id", nullable = false)
    public String subjectPrincipalId;

    @Column(name = "reason", nullable = false)
    public String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public CaseStatus status;

    @Column(name = "assigned_to")
    public String assignedTo;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    protected CaseEntity() {
        // JPA
    }

    public CaseEntity(UUID id, String subjectPrincipalId, String reason, CaseStatus status,
                      String assignedTo, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.subjectPrincipalId = subjectPrincipalId;
        this.reason = reason;
        this.status = status;
        this.assignedTo = assignedTo;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
