package com.sharkpay.reconciliation.storage;

import com.sharkpay.reconciliation.domain.ReconRun;
import com.sharkpay.reconciliation.ports.ReconRunRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for the {@code ReconRunRepository} port (id-keyed upsert).
 */
@Repository
public final class JpaReconRunRepository implements ReconRunRepository {

    private final ReconRunJpaRepository jpa;

    public JpaReconRunRepository(ReconRunJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(ReconRun run) {
        jpa.findById(run.id()).ifPresentOrElse(
                existing -> {
                    existing.applyDomain(run);
                    jpa.save(existing);
                },
                () -> jpa.save(ReconRunEntity.fromDomain(run)));
    }

    @Override
    public Optional<ReconRun> findById(String id) {
        return jpa.findById(id).map(ReconRunEntity::toDomain);
    }

    @Override
    public List<ReconRun> listByProvider(String provider) {
        return jpa.findByProviderOrderByStartedAtDescIdDesc(provider).stream()
                .map(ReconRunEntity::toDomain).toList();
    }
}
