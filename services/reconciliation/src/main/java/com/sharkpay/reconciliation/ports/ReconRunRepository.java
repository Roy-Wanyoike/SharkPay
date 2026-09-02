package com.sharkpay.reconciliation.ports;

import com.sharkpay.reconciliation.domain.ReconRun;

import java.util.List;
import java.util.Optional;

/**
 * Persistence of {@link ReconRun}s.
 */
public interface ReconRunRepository {

    /** Inserts or updates a run (id-keyed upsert). */
    void save(ReconRun run);

    Optional<ReconRun> findById(String id);

    /** Runs of one provider, newest first. */
    List<ReconRun> listByProvider(String provider);
}
