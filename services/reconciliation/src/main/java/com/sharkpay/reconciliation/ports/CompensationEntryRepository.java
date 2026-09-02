package com.sharkpay.reconciliation.ports;

import com.sharkpay.reconciliation.domain.CompensationEntry;

import java.util.List;
import java.util.Optional;

/**
 * Persistence of {@link CompensationEntry}s. Multiple compensations for
 * one break are legitimate (RB-7 rollback: a wrong compensation is
 * corrected by another compensation entry); their ledger keys differ by
 * sequence suffix, so each executes exactly once at the ledger.
 */
public interface CompensationEntryRepository {

    /** Inserts or updates an entry (id-keyed upsert). */
    void save(CompensationEntry entry);

    Optional<CompensationEntry> findById(String id);

    /** Compensations for one break, in proposal order. */
    List<CompensationEntry> listByBreak(String breakId);

    /** Number of compensations ever proposed for one break (key sequencing). */
    long countByBreak(String breakId);
}
