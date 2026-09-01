package com.sharkpay.wallet.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AppliedLedgerEventEntity} (event-id
 * dedup log).
 */
public interface AppliedLedgerEventJpaRepository extends JpaRepository<AppliedLedgerEventEntity, String> {
}
