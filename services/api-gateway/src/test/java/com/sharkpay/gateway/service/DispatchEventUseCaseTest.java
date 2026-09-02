package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.SubscriptionState;
import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.events.CloudEventEnvelope;
import com.sharkpay.gateway.events.EnvelopeCodec;
import com.sharkpay.gateway.events.EventTypeCatalog;
import com.sharkpay.gateway.fakes.InMemoryWebhookDeliveryRepository;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fan-out dispatcher: topic→catalog resolution, glob matching,
 * delivery idempotency (no double delivery), catalog closure and the
 * deterministic outbound payload.
 */
class DispatchEventUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final InMemoryWebhookSubscriptionRepository subscriptions =
            new InMemoryWebhookSubscriptionRepository();
    private final InMemoryWebhookDeliveryRepository deliveries =
            new InMemoryWebhookDeliveryRepository();
    private final EnvelopeCodec codec = new EnvelopeCodec(JsonMapper.builder().build());
    private final MutableClock clock = new MutableClock(NOW);
    private final SequentialRandomness randomness = new SequentialRandomness();
    private final DispatchEventUseCase dispatcher = new DispatchEventUseCase(subscriptions,
            deliveries, randomness, codec, clock);

    private com.sharkpay.gateway.domain.WebhookSubscription subscribe(UUID principal,
                                                                     String... patterns) {
        CreateWebhookSubscriptionUseCase create = new CreateWebhookSubscriptionUseCase(
                subscriptions, randomness, clock);
        return create.create(principal, "https://merchant.example.com/hooks",
                List.of(patterns), "whsec_0123456789abcdef");
    }

    private CloudEventEnvelope paymentSucceeded(String eventId) {
        ObjectNode data = codec.newDataObject();
        data.put("payment_id", "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A");
        data.put("state", "SUCCEEDED");
        return new CloudEventEnvelope(eventId, EventTypeCatalog.PAYMENT_SUCCEEDED.topic(), "1.0",
                "sharkpay/payments", "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", NOW, data);
    }

    @Test
    void fanOutCreatesOnePendingDeliveryPerMatchingActiveSubscription() {
        subscribe(PRINCIPAL, "payment.*");
        subscribe(PRINCIPAL, "payment.succeeded");
        subscribe(PRINCIPAL, "payout.*"); // non-matching
        subscribe(OTHER, "payment.*"); // another principal also gets its own

        int created = dispatcher.onEvent(paymentSucceeded(UUID.randomUUID().toString()));
        assertEquals(3, created);
        assertEquals(3, deliveries.all().size());
        for (WebhookDelivery delivery : deliveries.all().values()) {
            assertEquals(com.sharkpay.gateway.domain.DeliveryState.PENDING, delivery.state());
            assertEquals(0, delivery.attemptCount());
            assertEquals("payment.succeeded", delivery.eventType());
            assertTrue(delivery.dueAt(NOW));
        }
    }

    @Test
    void topicIsTranslatedToThePublicCatalogNameInAPayloadThatIsSigned() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = subscribe(PRINCIPAL,
                "payment.*");
        dispatcher.onEvent(paymentSucceeded(UUID.randomUUID().toString()));
        WebhookDelivery delivery = deliveries.all().values().iterator().next();

        assertEquals(subscription.id(), delivery.subscriptionId());
        // payload: exact JSON with the unversioned type, fixed field order
        String payload = delivery.payload();
        assertTrue(payload.startsWith("{\"id\":"));
        assertTrue(payload.contains("\"type\":\"payment.succeeded\""));
        assertTrue(payload.contains("\"specversion\":\"1.0\""));
        assertTrue(payload.contains("\"source\":\"sharkpay/payments\""));
        assertTrue(payload.contains("\"subject\":\"pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A\""));
        assertFalse(payload.contains(".v1"));
        // byte-identical across dispatches of the same envelope
        String again = codec.outboundPayload(paymentSucceeded(delivery.eventId()),
                "payment.succeeded");
        assertEquals(again, payload);
    }

    @Test
    void theSameEventIdIsNeverDeliveredTwicePerSubscription() {
        subscribe(PRINCIPAL, "payment.*");
        subscribe(OTHER, "payment.*");
        String eventId = UUID.randomUUID().toString();

        assertEquals(2, dispatcher.onEvent(paymentSucceeded(eventId)));
        assertEquals(0, dispatcher.onEvent(paymentSucceeded(eventId)));
        assertEquals(0, dispatcher.onEvent(paymentSucceeded(eventId)));
        assertEquals(2, deliveries.all().size());
    }

    @Test
    void pausedAndDeletedSubscriptionsReceiveNothing() {
        com.sharkpay.gateway.domain.WebhookSubscription paused = subscribe(PRINCIPAL,
                "payment.*");
        subscriptions.save(paused.paused(NOW));
        com.sharkpay.gateway.domain.WebhookSubscription deleted = subscribe(OTHER, "payment.*");
        subscriptions.save(deleted.deleted(NOW));
        com.sharkpay.gateway.domain.WebhookSubscription active = subscribe(OTHER,
                "payment.succeeded");

        assertEquals(1, dispatcher.onEvent(paymentSucceeded(UUID.randomUUID().toString())));
        assertEquals(1, deliveries.all().size());
        assertEquals(active.id(), deliveries.all().values().iterator().next().subscriptionId());
    }

    @Test
    void autoPausedDeadSubscriptionsReceiveNothingUntilResumed() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = subscribe(PRINCIPAL,
                "payment.*");
        com.sharkpay.gateway.domain.WebhookSubscription dead = subscription;
        for (int i = 0; i < 3; i++) {
            dead = subscriptions.save(dead.recordDeadDelivery(NOW).subscription());
        }
        assertEquals(0, dispatcher.onEvent(paymentSucceeded(UUID.randomUUID().toString())));

        subscriptions.save(dead.resumed(NOW));
        assertEquals(1, dispatcher.onEvent(paymentSucceeded(UUID.randomUUID().toString())));
    }

    @Test
    void unknownTopicsAreIgnoredCatalogClosure() {
        subscribe(PRINCIPAL, "*");
        assertEquals(0, dispatcher.onEvent(envelope("some.unknown.topic.v1")));
        assertEquals(0, deliveries.all().size());
    }

    @Test
    void internalOnlyTopicsHaveNoPublicNameAndAreIgnored() {
        subscribe(PRINCIPAL, "*");
        // risk.decision.v1 / risk.case.resolved.v1 / ledger.posting.committed.v1:
        // registered topics without a webhook catalog entry
        assertEquals(0, dispatcher.onEvent(envelope("risk.decision.v1")));
        assertEquals(0, dispatcher.onEvent(envelope("risk.case.resolved.v1")));
        assertEquals(0, dispatcher.onEvent(envelope("ledger.posting.committed.v1")));
        assertEquals(0, deliveries.all().size());
        // ...but they ARE known topics (intake accepts them)
        assertTrue(EventTypeCatalog.isKnownTopic("risk.decision.v1"));
        assertFalse(EventTypeCatalog.fromTopic("risk.decision.v1").isPresent());
    }

    @Test
    void differentEventIdsFanOutIndependently() {
        subscribe(PRINCIPAL, "payment.*");
        assertEquals(1, dispatcher.onEvent(paymentSucceeded(UUID.randomUUID().toString())));
        assertEquals(1, dispatcher.onEvent(paymentSucceeded(UUID.randomUUID().toString())));
        assertEquals(2, deliveries.all().size());
        assertNotEquals(deliveries.all().values().stream().findFirst().orElseThrow().id(),
                deliveries.all().values().stream().skip(1).findFirst().orElseThrow().id());
    }

    @Test
    void deliveriesAreCreatedForEveryCatalogTopicFamily() {
        subscribe(PRINCIPAL, "*");
        for (EventTypeCatalog event : EventTypeCatalog.values()) {
            assertEquals(1, dispatcher.onEvent(envelope(event.topic())),
                    "topic " + event.topic() + " must fan out");
        }
        assertEquals(EventTypeCatalog.values().length, deliveries.all().size());
    }

    private CloudEventEnvelope envelope(String topic) {
        ObjectNode data = codec.newDataObject();
        data.put("subject_id", "ent_01HZWR4Z7K8Q2N5M9X3V1B6Y0A");
        return new CloudEventEnvelope(UUID.randomUUID().toString(), topic, "1.0",
                "sharkpay/payments", "ent_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", NOW, data);
    }
}
