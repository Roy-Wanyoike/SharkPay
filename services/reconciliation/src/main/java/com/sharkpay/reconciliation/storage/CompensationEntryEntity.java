package com.sharkpay.reconciliation.storage;

import com.sharkpay.reconciliation.domain.CompensationEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code compensation_entries} table: the 4-eyes
 * compensation records. The schema enforces the two-person control
 * (requester ≠ approver once approved) and the exactly-once execution
 * shape (executed ⇒ journal entry id + approver + timestamp all present).
 */
@Entity
@Table(name = "compensation_entries")
public class CompensationEntryEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "break_id", nullable = false, length = 40)
    private String breakId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "compensation_key", nullable = false, length = 128)
    private String compensationKey;

    @Column(name = "requester", nullable = false, length = 128)
    private String requester;

    @Column(name = "approver", length = 128)
    private String approver;

    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    @Column(name = "legs_json", nullable = false, columnDefinition = "text")
    private String legsJson;

    @Column(name = "reverses_entry_id")
    private UUID reversesEntryId;

    @Column(name = "state", nullable = false, length = 10)
    private String state;

    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "ledger_replay", nullable = false)
    private boolean ledgerReplay;

    protected CompensationEntryEntity() {
    }

    public CompensationEntryEntity(String id, String breakId, String provider, String compensationKey,
                                    String requester, String approver, String reason, String legsJson,
                                    UUID reversesEntryId, String state, UUID ledgerEntryId,
                                    Instant executedAt, boolean ledgerReplay) {
        this.id = id;
        this.breakId = breakId;
        this.provider = provider;
        this.compensationKey = compensationKey;
        this.requester = requester;
        this.approver = approver;
        this.reason = reason;
        this.legsJson = legsJson;
        this.reversesEntryId = reversesEntryId;
        this.state = state;
        this.ledgerEntryId = ledgerEntryId;
        this.executedAt = executedAt;
        this.ledgerReplay = ledgerReplay;
    }

    /** Maps the domain entry onto a fresh entity (insert shape). */
    public static CompensationEntryEntity fromDomain(CompensationEntry entry) {
        return new CompensationEntryEntity(entry.id(), entry.breakId(), entry.provider(),
                entry.compensationKey(), entry.requester(), entry.approver(), entry.reason(),
                StorageJson.writeLegs(entry.legs()), entry.reversesEntryId(),
                entry.state().wireName(), entry.ledgerEntryId(), entry.executedAt(),
                entry.ledgerReplay());
    }

    /** Applies the mutable execution fields onto this entity (update shape). */
    public void applyDomain(CompensationEntry entry) {
        this.approver = entry.approver();
        this.state = entry.state().wireName();
        this.ledgerEntryId = entry.ledgerEntryId();
        this.executedAt = entry.executedAt();
        this.ledgerReplay = entry.ledgerReplay();
    }

    /** Restores the domain aggregate. */
    public CompensationEntry toDomain() {
        return CompensationEntry.rehydrate(id, breakId, provider, compensationKey, requester,
                reason, StorageJson.readLegs(legsJson), reversesEntryId,
                CompensationEntry.CompensationState.fromWireName(state), approver, ledgerEntryId,
                executedAt, ledgerReplay);
    }

    public String getId() {
        return id;
    }
}
