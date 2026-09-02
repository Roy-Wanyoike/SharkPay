package com.sharkpay.reconciliation.ports;

import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.ReconBreak;

import java.util.List;
import java.util.Optional;

/**
 * Persistence of {@link ReconBreak}s. Aging buckets are persisted by the
 * sweeper and recomputed live on the read side — both views derive from
 * the same pure {@code AgingBucket.of(detectedAt, now)}.
 */
public interface ReconBreakRepository {

    /** Inserts or updates a break (id-keyed upsert). */
    void save(ReconBreak break_);

    Optional<ReconBreak> findById(String id);

    /** Breaks of one run, in detection order. */
    List<ReconBreak> listByRun(String runId);

    /** Breaks in one state, detection order. */
    List<ReconBreak> listByState(BreakState state);

    /** Breaks in one of the active states (OPEN or INVESTIGATING) — sweeper input. */
    List<ReconBreak> listActive();

    /** Breaks of one provider (any state), detection order. */
    List<ReconBreak> listByProvider(String provider);
}
