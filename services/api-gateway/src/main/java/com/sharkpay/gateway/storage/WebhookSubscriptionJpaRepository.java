package com.sharkpay.gateway.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link WebhookSubscriptionEntity}.
 */
public interface WebhookSubscriptionJpaRepository
        extends JpaRepository<WebhookSubscriptionEntity, String> {

    List<WebhookSubscriptionEntity> findByPrincipalIdOrderByIdAsc(UUID principalId);

    List<WebhookSubscriptionEntity> findByStateOrderByIdAsc(String state);
}
