package com.sharkpay.wallet.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the append-only {@code applied_ledger_events} dedup table
 * (the CloudEvent envelope id, opaque text).
 */
@Entity
@Table(name = "applied_ledger_events")
public class AppliedLedgerEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    protected AppliedLedgerEventEntity() {
    }

    public AppliedLedgerEventEntity(String eventId, UUID entryId, Instant appliedAt) {
        this.eventId = eventId;
        this.entryId = entryId;
        this.appliedAt = appliedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
