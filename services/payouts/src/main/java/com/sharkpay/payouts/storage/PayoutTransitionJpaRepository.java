package com.sharkpay.payouts.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data surface of the payout transition audit table. */
public interface PayoutTransitionJpaRepository extends JpaRepository<PayoutTransitionEntity, Long> {

    List<PayoutTransitionEntity> findByPayoutIdOrderByIdAsc(String payoutId);
}
