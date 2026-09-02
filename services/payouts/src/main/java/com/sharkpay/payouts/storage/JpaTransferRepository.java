package com.sharkpay.payouts.storage;

import com.sharkpay.payouts.domain.StateTransition;
import com.sharkpay.payouts.domain.Transfer;
import com.sharkpay.payouts.ports.TransferRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA adapter for the transfer repository port: delegation + entity mapping
 * (no business logic — the domain owns the rules). Saving persists the
 * aggregate and appends its pending transition rows to the audit table;
 * loading rehydrates the full timeline. Component-scanned production
 * adapter (mirrors the wallet service's storage package); local tests run
 * on the in-tree fake per ADR 003.
 */
@Repository
public final class JpaTransferRepository implements TransferRepository {

    private final TransferJpaRepository jpa;
    private final TransferTransitionJpaRepository transitions;

    public JpaTransferRepository(TransferJpaRepository jpa,
                                 TransferTransitionJpaRepository transitions) {
        this.jpa = Objects.requireNonNull(jpa, "transferJpaRepository is required");
        this.transitions = Objects.requireNonNull(transitions,
                "transferTransitionJpaRepository is required");
    }

    @Override
    @Transactional
    public Transfer save(Transfer transfer) {
        Objects.requireNonNull(transfer, "transfer is required");
        TransferEntity entity = jpa.findById(transfer.id())
                .map(existing -> {
                    existing.applyDomain(transfer);
                    return existing;
                })
                .orElseGet(() -> TransferEntity.fromDomain(transfer));
        jpa.save(entity);
        for (StateTransition transition : transfer.pendingTransitions()) {
            transitions.save(TransferTransitionEntity.of(transfer, transition));
        }
        transfer.markTransitionsPersisted();
        return transfer;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Transfer> findById(String transferId) {
        return jpa.findById(transferId == null ? "" : transferId.trim())
                .map(entity -> entity.toDomain(historyOf(entity.id)));
    }

    private List<StateTransition> historyOf(String transferId) {
        return transitions.findByTransferIdOrderByIdAsc(transferId).stream()
                .map(JpaTransferRepository::toDomainTransition)
                .toList();
    }

    private static StateTransition toDomainTransition(TransferTransitionEntity entity) {
        return new StateTransition(com.sharkpay.payouts.domain.TransferState.fromWire(
                entity.fromState), com.sharkpay.payouts.domain.TransferState.fromWire(
                entity.toState), entity.trigger, entity.actor, entity.note, entity.createdAt);
    }
}
