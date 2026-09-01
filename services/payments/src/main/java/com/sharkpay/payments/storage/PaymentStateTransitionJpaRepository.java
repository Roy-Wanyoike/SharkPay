package com.sharkpay.payments.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link PaymentStateTransitionEntity}
 * (payment_state_transitions, append-only).
 */
public interface PaymentStateTransitionJpaRepository
        extends JpaRepository<PaymentStateTransitionEntity, PaymentStateTransitionId> {

    List<PaymentStateTransitionEntity> findByPaymentIdOrderBySeqAsc(String paymentId);
}
