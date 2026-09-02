package com.sharkpay.payouts.storage;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.StateTransition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping of the append-only {@code payout_state_transitions} audit
 * table: one row per recorded state change (docs/DATA-MACHINES §1). The
 * bigserial id preserves the insert (chronological) order.
 */
@Entity
@Table(name = "payout_state_transitions")
public class PayoutTransitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    public Long id;

    @Column(name = "payout_id", nullable = false, updatable = false, length = 40)
    public String payoutId;

    @Column(name = "from_state", nullable = false, updatable = false, length = 16)
    public String fromState;

    @Column(name = "to_state", nullable = false, updatable = false, length = 16)
    public String toState;

    @Column(name = "trigger", nullable = false, updatable = false, length = 24)
    public String trigger;

    @Column(name = "actor", nullable = false, updatable = false, length = 16)
    public String actor;

    @Column(name = "note", updatable = false, length = 512)
    public String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    public static PayoutTransitionEntity of(Payout payout, StateTransition transition) {
        PayoutTransitionEntity entity = new PayoutTransitionEntity();
        entity.payoutId = payout.id();
        entity.fromState = transition.fromWire();
        entity.toState = transition.toWire();
        entity.trigger = transition.trigger();
        entity.actor = transition.actor();
        entity.note = transition.note();
        entity.createdAt = transition.occurredAt();
        return entity;
    }
}
