package com.sharkpay.reconciliation.storage;

import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for the {@code ReconBreakRepository} port. Saves are
 * id-keyed upserts (applyDomain on the persisted shape) so the domain
 * aggregate's mutations persist in place — history is never rewritten,
 * only lifecycle columns move.
 */
@Repository
public final class JpaReconBreakRepository implements ReconBreakRepository {

    private final ReconBreakJpaRepository jpa;

    public JpaReconBreakRepository(ReconBreakJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(ReconBreak break_) {
        jpa.findById(break_.id()).ifPresentOrElse(
                existing -> {
                    existing.applyDomain(break_);
                    jpa.save(existing);
                },
                () -> jpa.save(ReconBreakEntity.fromDomain(break_)));
    }

    @Override
    public Optional<ReconBreak> findById(String id) {
        return jpa.findById(id).map(ReconBreakEntity::toDomain);
    }

    @Override
    public List<ReconBreak> listByRun(String runId) {
        return jpa.findByRunIdOrderByDetectedAtAscIdAsc(runId).stream()
                .map(ReconBreakEntity::toDomain).toList();
    }

    @Override
    public List<ReconBreak> listByState(BreakState state) {
        return jpa.findByStateOrderByDetectedAtAscIdAsc(state.wireName()).stream()
                .map(ReconBreakEntity::toDomain).toList();
    }

    @Override
    public List<ReconBreak> listActive() {
        return jpa.findByStatesOrderByDetectedAtAscIdAsc(
                        List.of(BreakState.OPEN.wireName(), BreakState.INVESTIGATING.wireName()))
                .stream().map(ReconBreakEntity::toDomain).toList();
    }

    @Override
    public List<ReconBreak> listByProvider(String provider) {
        return jpa.findByProviderOrderByDetectedAtAscIdAsc(provider).stream()
                .map(ReconBreakEntity::toDomain).toList();
    }
}
