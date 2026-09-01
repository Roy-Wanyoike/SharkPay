package com.sharkpay.payments.storage;

import com.sharkpay.payments.domain.PaymentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the append-only {@code payment_state_transitions} audit
 * table: one row per state change (STATE-MACHINES.md §7.3 replayability —
 * "transitions + Temporal history reconstruct any payment's full timeline").
 * The creation row carries {@code fromState == null}.
 */
@Entity
@Table(name = "payment_state_transitions")
@IdClass(PaymentStateTransitionId.class)
public class PaymentStateTransitionEntity {

    @Id
    @Column(name = "payment_id", nullable = false, length = 40)
    private String paymentId;

    @Id
    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "from_state", length = 16)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 16)
    private String toState;

    @Column(name = "reason")
    private String reason;

    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PaymentStateTransitionEntity() {
    }

    public PaymentStateTransitionEntity(String paymentId, long seq, String fromState,
                                        String toState, String reason, UUID entryId,
                                        Instant occurredAt) {
        this.paymentId = paymentId;
        this.seq = seq;
        this.fromState = fromState;
        this.toState = toState;
        this.reason = reason;
        this.entryId = entryId;
        this.occurredAt = occurredAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public long getSeq() {
        return seq;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }

    public String getReason() {
        return reason;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** Wire names only — the domain enum validates them on rehydration. */
    static String wireOf(PaymentState state) {
        return state == null ? null : state.wireName();
    }
}
