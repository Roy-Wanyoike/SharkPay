package com.sharkpay.payouts.fakes;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.StateTransition;
import com.sharkpay.payouts.ports.PayoutRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory payout repository (in-tree test fake, src/test, per ADR 003).
 * Mirrors the JPA adapter semantics: save persists the aggregate and drains
 * its pending transitions into the append-only log; the three scheduler
 * queries reproduce the partial-index semantics of V1__payouts_init.sql
 * (due-for-release, TTL expiry sweep, in-flight polling) including their
 * ORDER BY so batch-exactness tests are meaningful.
 */
public final class InMemoryPayoutRepository implements PayoutRepository {

    private final Map<String, Payout> payouts = new ConcurrentHashMap<>();
    private final Map<String, List<StateTransition>> transitions = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public Payout save(Payout payout) {
        payouts.put(payout.id(), payout);
        transitions.computeIfAbsent(payout.id(), key -> new ArrayList<>())
                .addAll(payout.pendingTransitions());
        payout.markTransitionsPersisted();
        return payout;
    }

    @Override
    public Optional<Payout> findById(String payoutId) {
        return Optional.ofNullable(payouts.get(payoutId == null ? "" : payoutId.trim()));
    }

    @Override
    public List<Payout> findDueForRelease(java.time.Instant now, int limit) {
        return payouts.values().stream()
                .filter(payout -> payout.state() == PayoutState.PENDING_RISK)
                .filter(payout -> payout.executeAfter() == null
                        || !payout.executeAfter().isAfter(now))
                .filter(payout -> payout.nextAttemptAt() == null
                        || !payout.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(Payout::executeAfter,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Payout::id))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<Payout> findExpired(java.time.Instant now, int limit) {
        return payouts.values().stream()
                .filter(payout -> payout.state() == PayoutState.PENDING_RISK
                        || payout.state() == PayoutState.PROCESSING)
                .filter(payout -> payout.expiresAt().isBefore(now))
                .sorted(Comparator.comparing(Payout::expiresAt).thenComparing(Payout::id))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<Payout> findInFlight(int limit) {
        return payouts.values().stream()
                .filter(payout -> payout.state() == PayoutState.PROCESSING
                        || payout.state() == PayoutState.SENT)
                .sorted(Comparator.comparing(Payout::updatedAt).thenComparing(Payout::id))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public long countByState(PayoutState state) {
        return payouts.values().stream().filter(payout -> payout.state() == state).count();
    }

    /** Number of stored payouts (all states). */
    public int count() {
        return payouts.size();
    }

    /** The append-only transition log of one payout, oldest first. */
    public List<StateTransition> transitionsOf(String payoutId) {
        return List.copyOf(transitions.getOrDefault(payoutId, List.of()));
    }

    /**
     * Drops a payout and its transition rows (simulates a lost row — the
     * idempotency key still points at it).
     */
    public void remove(String payoutId) {
        payouts.remove(payoutId);
        transitions.remove(payoutId);
    }

    /** The internal bigserial counter (diagnostics). */
    public long transitionRows() {
        return transitions.values().stream().mapToLong(List::size).sum();
    }
}
