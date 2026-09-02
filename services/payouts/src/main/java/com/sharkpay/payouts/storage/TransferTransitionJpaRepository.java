package com.sharkpay.payouts.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data surface of the transfer transition audit table. */
public interface TransferTransitionJpaRepository extends JpaRepository<TransferTransitionEntity, Long> {

    List<TransferTransitionEntity> findByTransferIdOrderByIdAsc(String transferId);
}
