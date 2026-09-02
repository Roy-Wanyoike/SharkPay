package com.sharkpay.payouts.ports;

import com.sharkpay.payouts.domain.Transfer;

import java.util.Optional;

/**
 * Transfer persistence port. {@link #save(Transfer)} upserts the aggregate
 * and appends its {@link Transfer#pendingTransitions()} to the
 * {@code transfer_state_transitions} audit table before marking them
 * persisted. Production: the JPA adapter (storage package); tests: the
 * in-tree in-memory fake.
 */
public interface TransferRepository {

    Transfer save(Transfer transfer);

    Optional<Transfer> findById(String transferId);
}
