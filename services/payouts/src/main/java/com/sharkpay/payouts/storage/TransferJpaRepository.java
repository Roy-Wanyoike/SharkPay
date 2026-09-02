package com.sharkpay.payouts.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data surface of the {@code transfers} table. */
public interface TransferJpaRepository extends JpaRepository<TransferEntity, String> {

    Optional<TransferEntity> findByInternalRef(UUID internalRef);
}
