package com.sharkpay.reconciliation.storage;

import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.ports.CompensationEntryRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for the {@code CompensationEntryRepository} port (id-keyed
 * upsert). The unique constraint on {@code compensation_key} is the
 * database-side second line of exactly-once defence (the ledger's
 * transaction-key idempotency is the first).
 */
@Repository
public final class JpaCompensationEntryRepository implements CompensationEntryRepository {

    private final CompensationEntryJpaRepository jpa;

    public JpaCompensationEntryRepository(CompensationEntryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(CompensationEntry entry) {
        jpa.findById(entry.id()).ifPresentOrElse(
                existing -> {
                    existing.applyDomain(entry);
                    jpa.save(existing);
                },
                () -> jpa.save(CompensationEntryEntity.fromDomain(entry)));
    }

    @Override
    public Optional<CompensationEntry> findById(String id) {
        return jpa.findById(id).map(CompensationEntryEntity::toDomain);
    }

    @Override
    public List<CompensationEntry> listByBreak(String breakId) {
        return jpa.findByBreakIdOrderByCompensationKeyAsc(breakId).stream()
                .map(CompensationEntryEntity::toDomain).toList();
    }

    @Override
    public long countByBreak(String breakId) {
        return jpa.countByBreakId(breakId);
    }
}
