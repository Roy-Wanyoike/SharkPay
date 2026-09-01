package com.sharkpay.wallet.storage;

import com.sharkpay.wallet.domain.PostingSequence;
import com.sharkpay.wallet.domain.ProjectionLeg;
import com.sharkpay.wallet.domain.StatementLine;
import com.sharkpay.wallet.ports.ProjectionStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA adapter for the projection store port. Legs live in the append-only
 * {@code wallet_postings} table; on every newly inserted leg the wallet's
 * whole sequence is re-folded through the domain {@link PostingSequence} (in
 * posting_id order) and the {@code balance_after} values are rewritten —
 * exactly the in-memory semantics, so out-of-order delivery converges and
 * duplicate legs are no-ops (the (wallet_id, posting_id) primary key is the
 * dedup).
 */
@Repository
public final class JpaProjectionStore implements ProjectionStore {

    private final WalletPostingJpaRepository postings;
    private final AppliedLedgerEventJpaRepository appliedEvents;

    public JpaProjectionStore(WalletPostingJpaRepository postings,
                              AppliedLedgerEventJpaRepository appliedEvents) {
        this.postings = postings;
        this.appliedEvents = appliedEvents;
    }

    @Override
    public boolean isEventApplied(String eventId) {
        return eventId != null && appliedEvents.existsById(eventId);
    }

    @Override
    public void markEventApplied(String eventId, UUID entryId) {
        if (eventId != null && !appliedEvents.existsById(eventId)) {
            appliedEvents.save(new AppliedLedgerEventEntity(eventId, entryId, Instant.now()));
        }
    }

    @Override
    public boolean applyLeg(String walletId, ProjectionLeg leg) {
        try {
            postings.saveAndFlush(WalletPostingEntity.fromLeg(walletId, leg));
        } catch (DataIntegrityViolationException duplicate) {
            return false;   // (wallet_id, posting_id) already projected
        }
        recomputeBalances(walletId, leg.amount().currency());
        return true;
    }

    @Override
    public List<StatementLine> statement(String walletId, int limit, Long afterPostingId) {
        long after = afterPostingId == null ? 0L : afterPostingId;
        return postings.findNext(walletId, after, Limit.of(Math.max(0, limit))).stream()
                .map(WalletPostingEntity::toDomain)
                .toList();
    }

    @Override
    public long totalMinor(String walletId) {
        return postings.findLast(walletId, Limit.of(1)).stream()
                .findFirst()
                .map(WalletPostingEntity::getBalanceAfter)
                .orElse(0L);
    }

    /** Re-folds the wallet's legs in posting order and rewrites balance_after. */
    private void recomputeBalances(String walletId, String currency) {
        List<WalletPostingEntity> lines = postings.findAllOrdered(walletId);
        PostingSequence sequence = new PostingSequence(walletId, currency);
        for (WalletPostingEntity entity : lines) {
            sequence.apply(entity.toLeg());
        }
        List<StatementLine> statement = sequence.statement();
        for (int i = 0; i < lines.size(); i++) {
            WalletPostingEntity entity = lines.get(i);
            entity.setBalanceAfter(statement.get(i).balanceAfter().amountMinor());
            postings.save(entity);
        }
    }
}
