package com.sharkpay.gateway.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link WebhookDeliveryEntity}.
 */
public interface WebhookDeliveryJpaRepository extends JpaRepository<WebhookDeliveryEntity, String> {

    Optional<WebhookDeliveryEntity> findBySubscriptionIdAndEventId(String subscriptionId,
                                                                   String eventId);

    List<WebhookDeliveryEntity> findBySubscriptionId(String subscriptionId);

    List<WebhookDeliveryEntity> findByStateOrderByNextAttemptAtAsc(String state);
}
