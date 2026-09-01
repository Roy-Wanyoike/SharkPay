package com.sharkpay.wallet.fakes;

import com.sharkpay.wallet.domain.PostingSequence;
import com.sharkpay.wallet.domain.ProjectionLeg;
import com.sharkpay.wallet.domain.StatementLine;
import com.sharkpay.wallet.ports.ProjectionStore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory projection store (in-tree test fake (src/test, per ADR 003)). Each
 * wallet's legs live in a domain {@link PostingSequence}, so the in-memory
 * semantics and the JPA adapter share the exact same ordering, dedup and
 * money-safety rules.
 */
public final class InMemoryProjectionStore implements ProjectionStore {

    private final Map<String, PostingSequence> sequencesByWallet = new ConcurrentHashMap<>();
    private final Set<String> appliedEvents = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isEventApplied(String eventId) {
        return eventId != null && appliedEvents.contains(eventId);
    }

    @Override
    public void markEventApplied(String eventId, java.util.UUID entryId) {
        if (eventId != null) {
            appliedEvents.add(eventId);
        }
    }

    @Override
    public boolean applyLeg(String walletId, ProjectionLeg leg) {
        PostingSequence sequence = sequencesByWallet.computeIfAbsent(walletId,
                id -> new PostingSequence(id, leg.amount().currency()));
        return sequence.apply(leg);
    }

    @Override
    public List<StatementLine> statement(String walletId, int limit, Long afterPostingId) {
        PostingSequence sequence = sequencesByWallet.get(walletId);
        if (sequence == null) {
            return List.of();
        }
        return sequence.statement().stream()
                .filter(line -> afterPostingId == null || line.leg().postingId() > afterPostingId)
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public long totalMinor(String walletId) {
        PostingSequence sequence = sequencesByWallet.get(walletId);
        return sequence == null ? 0L : sequence.totalMinor();
    }

    /** Number of distinct applied ledger events (test assertions). */
    public int appliedEventCount() {
        return appliedEvents.size();
    }

    /** The wallet's posting sequence (test assertions). */
    public PostingSequence sequence(String walletId) {
        return sequencesByWallet.get(walletId);
    }

    /** Number of wallets with projection lines. */
    public int projectedWalletCount() {
        return sequencesByWallet.size();
    }
}
