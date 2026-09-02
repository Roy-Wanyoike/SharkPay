package com.sharkpay.payouts.fakes;

import com.sharkpay.payouts.domain.StateTransition;
import com.sharkpay.payouts.domain.Transfer;
import com.sharkpay.payouts.ports.TransferRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory transfer repository (in-tree test fake, src/test, per ADR 003).
 * Mirrors the JPA adapter: save persists the aggregate and drains its
 * pending transitions into the append-only log.
 */
public final class InMemoryTransferRepository implements TransferRepository {

    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final Map<String, List<StateTransition>> transitions = new ConcurrentHashMap<>();

    @Override
    public Transfer save(Transfer transfer) {
        transfers.put(transfer.id(), transfer);
        transitions.computeIfAbsent(transfer.id(), key -> new ArrayList<>())
                .addAll(transfer.pendingTransitions());
        transfer.markTransitionsPersisted();
        return transfer;
    }

    @Override
    public Optional<Transfer> findById(String transferId) {
        return Optional.ofNullable(transfers.get(transferId == null ? "" : transferId.trim()));
    }

    /** Number of stored transfers (all states). */
    public int count() {
        return transfers.size();
    }

    /** The append-only transition log of one transfer, oldest first. */
    public List<StateTransition> transitionsOf(String transferId) {
        return List.copyOf(transitions.getOrDefault(transferId, List.of()));
    }

    /**
     * Drops a transfer and its transition rows (simulates a lost row — the
     * idempotency key still points at it).
     */
    public void remove(String transferId) {
        transfers.remove(transferId);
        transitions.remove(transferId);
    }
}
