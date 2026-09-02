package com.sharkpay.payouts.storage;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.StateTransition;
import com.sharkpay.payouts.ports.PayoutRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA adapter for the payout repository port: delegation + entity mapping
 * (no business logic — the domain owns the rules). Saving persists the
 * aggregate and appends its pending transition rows to the audit table;
 * loading rehydrates the full timeline. The three scheduler queries mirror
 * the partial indexes of V1__payouts_init.sql. Component-scanned production
 * adapter; local tests run on the in-tree fake per ADR 003.
 */
@Repository
public final class JpaPayoutRepository implements PayoutRepository {

    private final PayoutJpaRepository jpa;
    private final PayoutTransitionJpaRepository transitions;

    public JpaPayoutRepository(PayoutJpaRepository jpa, PayoutTransitionJpaRepository transitions) {
        this.jpa = Objects.requireNonNull(jpa, "payoutJpaRepository is required");
        this.transitions = Objects.requireNonNull(transitions,
                "payoutTransitionJpaRepository is required");
    }

    @Override
    @Transactional
    public Payout save(Payout payout) {
        Objects.requireNonNull(payout, "payout is required");
        PayoutEntity entity = jpa.findById(payout.id())
                .map(existing -> {
                    existing.applyDomain(payout);
                    return existing;
                })
                .orElseGet(() -> PayoutEntity.fromDomain(payout));
        jpa.save(entity);
        for (StateTransition transition : payout.pendingTransitions()) {
            transitions.save(PayoutTransitionEntity.of(payout, transition));
        }
        payout.markTransitionsPersisted();
        return payout;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payout> findById(String payoutId) {
        return jpa.findById(payoutId == null ? "" : payoutId.trim())
                .map(entity -> entity.toDomain(historyOf(entity.id)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payout> findDueForRelease(Instant now, int limit) {
        return jpa.findDueForRelease(now, Limit.of(limit)).stream()
                .map(entity -> entity.toDomain(historyOf(entity.id)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payout> findExpired(Instant now, int limit) {
        return jpa.findExpired(now, Limit.of(limit)).stream()
                .map(entity -> entity.toDomain(historyOf(entity.id)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payout> findInFlight(int limit) {
        return jpa.findInFlight(Limit.of(limit)).stream()
                .map(entity -> entity.toDomain(historyOf(entity.id)))
                .toList();
    }

    @Override
    public long countByState(PayoutState state) {
        return jpa.countByState(state);
    }

    private List<StateTransition> historyOf(String payoutId) {
        return transitions.findByPayoutIdOrderByIdAsc(payoutId).stream()
                .map(JpaPayoutRepository::toDomainTransition)
                .toList();
    }

    private static StateTransition toDomainTransition(PayoutTransitionEntity entity) {
        return new StateTransition(PayoutState.fromWire(entity.fromState),
                PayoutState.fromWire(entity.toState), entity.trigger, entity.actor, entity.note,
                entity.createdAt);
    }
}
