package com.sharkpay.payments.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * Spring Data repository for {@link PaymentIntentEntity}
 * (payment_intents). Listing filters are applied by the port adapter
 * (wallet-consistent V1 stance).
 */
public interface PaymentIntentJpaRepository extends JpaRepository<PaymentIntentEntity, String> {

    List<PaymentIntentEntity> findAll(Sort sort);

    boolean existsByPrincipalId(java.util.UUID principalId);
}
