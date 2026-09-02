package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.DeliveryState;
import com.sharkpay.gateway.domain.EventPattern;
import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.domain.WebhookSignature;
import com.sharkpay.gateway.fakes.InMemoryWebhookDeliveryRepository;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery-log read model + operator replay: only dead deliveries can be
 * re-queued (409 otherwise); replay resets the attempt counter and makes
 * the delivery immediately due.
 */
class WebhookDeliveryUseCaseTest {

    private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();

    private final InMemoryWebhookSubscriptionRepository subscriptions =
            new InMemoryWebhookSubscriptionRepository();
    private final InMemoryWebhookDeliveryRepository deliveries =
            new InMemoryWebhookDeliveryRepository();
    private final MutableClock clock = new MutableClock(START);
    private final WebhookSubscriptionLifecycleUseCase lifecycle =
            new WebhookSubscriptionLifecycleUseCase(subscriptions, clock);
    private final WebhookDeliveryUseCase useCase =
            new WebhookDeliveryUseCase(deliveries, lifecycle, clock);

    private WebhookSubscription subscribe() {
        return subscriptions.save(WebhookSubscription.active("wh_0000000000000000001",
                PRINCIPAL, "https://merchant.example.com/hooks",
                Set.of(EventPattern.of("payment.*")), "whsec_0123456789abcdef", START));
    }

    private WebhookDelivery delivery(WebhookSubscription subscription, String id,
                                     DeliveryState state, int attempts) {
        String payload = "{\"id\":\"evt-" + id + "\",\"type\":\"payment.succeeded\"}";
        return switch (state) {
            case PENDING -> deliveries.save(WebhookDelivery.pending(id, subscription.id(),
                    "evt-" + id, "payment.succeeded", payload, clock.instant()));
            case DELIVERED -> deliveries.save(WebhookDelivery.pending(id, subscription.id(),
                    "evt-" + id, "payment.succeeded", payload, clock.instant())
                    .succeeded(200, clock.instant()));
            case DEAD -> {
                WebhookDelivery dead = WebhookDelivery.pending(id, subscription.id(),
                        "evt-" + id, "payment.succeeded", payload, clock.instant());
                for (int i = 0; i < attempts; i++) {
                    dead = dead.attemptFailed(500, "http 500", clock.instant());
                }
                yield deliveries.save(dead);
            }
        };
    }

    @Test
    void listIsNewestFirstScopedToTheOwnedEndpoint() {
        WebhookSubscription subscription = subscribe();
        WebhookSubscription foreign = subscriptions.save(WebhookSubscription.active(
                "wh_0000000000000000002", UUID.randomUUID(),
                "https://other.example.com/hooks", Set.of(EventPattern.of("payment.*")),
                "whsec_0123456789abcdef", START));

        WebhookDelivery first = delivery(subscription, "whd_older000000000000000001",
                DeliveryState.PENDING, 0);
        clock.advance(Duration.ofMinutes(1));
        WebhookDelivery second = delivery(subscription, "whd_newer000000000000000002",
                DeliveryState.DELIVERED, 0);
        delivery(foreign, "whd_foreign00000000000000003", DeliveryState.PENDING, 0);

        List<WebhookDelivery> page = useCase.list(subscription.id(), PRINCIPAL, 50, null);
        assertEquals(2, page.size());
        assertEquals(second.id(), page.get(0).id());
        assertEquals(first.id(), page.get(1).id());

        // cursor pagination follows the newest-first order
        List<WebhookDelivery> nextPage = useCase.list(subscription.id(), PRINCIPAL, 1,
                second.id());
        assertEquals(1, nextPage.size());
        assertEquals(first.id(), nextPage.get(0).id());
    }

    @Test
    void listRequiresOwnershipOfTheEndpoint() {
        WebhookSubscription subscription = subscribe();
        assertThrows(NoSuchElementException.class,
                () -> useCase.list(subscription.id(), UUID.randomUUID(), 50, null));
        assertThrows(NoSuchElementException.class,
                () -> useCase.list("wh_missing00000000000000000", PRINCIPAL, 50, null));
    }

    @Test
    void replayRequeuesOnlyDeadDeliveries() {
        WebhookSubscription subscription = subscribe();
        WebhookDelivery dead = delivery(subscription, "whd_dead0000000000000000001",
                DeliveryState.DEAD, 8);
        assertEquals(DeliveryState.DEAD, dead.state());
        assertEquals(8, dead.attemptCount());

        clock.advance(Duration.ofMinutes(30));
        WebhookDelivery replayed = useCase.replay(subscription.id(), dead.id(), PRINCIPAL);
        assertEquals(DeliveryState.PENDING, replayed.state());
        assertEquals(0, replayed.attemptCount());
        assertEquals(clock.instant(), replayed.nextAttemptAt());
        assertTrue(replayed.dueAt(clock.instant()));
        // the failure history is preserved for the Console
        assertEquals(500, replayed.lastResponseCode());
        assertEquals("http 500", replayed.lastError());
    }

    @Test
    void pendingAndDeliveredDeliveriesRefuseReplay() {
        WebhookSubscription subscription = subscribe();
        WebhookDelivery pending = delivery(subscription, "whd_pending000000000000000001",
                DeliveryState.PENDING, 0);
        WebhookDelivery delivered = delivery(subscription, "whd_delivered00000000000000002",
                DeliveryState.DELIVERED, 0);

        assertThrows(com.sharkpay.gateway.domain.DeliveryNotReplayableException.class,
                () -> useCase.replay(subscription.id(), pending.id(), PRINCIPAL));
        assertThrows(com.sharkpay.gateway.domain.DeliveryNotReplayableException.class,
                () -> useCase.replay(subscription.id(), delivered.id(), PRINCIPAL));
        // nothing changed
        assertEquals(DeliveryState.PENDING, deliveries.findById(pending.id()).orElseThrow()
                .state());
        assertEquals(DeliveryState.DELIVERED, deliveries.findById(delivered.id()).orElseThrow()
                .state());
    }

    @Test
    void replayScopesToTheOwnedEndpointAndMatchingDelivery() {
        WebhookSubscription subscription = subscribe();
        WebhookSubscription other = subscribeOther();
        WebhookDelivery dead = delivery(other, "whd_otherdead00000000000000001",
                DeliveryState.DEAD, 8);

        // foreign endpoint: 404
        assertThrows(NoSuchElementException.class,
                () -> useCase.replay(other.id(), dead.id(), PRINCIPAL));
        // delivery belonging to another subscription: 404
        assertThrows(NoSuchElementException.class,
                () -> useCase.replay(subscription.id(), dead.id(), PRINCIPAL));
        assertThrows(NoSuchElementException.class,
                () -> useCase.replay(subscription.id(), "whd_missing0000000000000001", PRINCIPAL));
        assertDoesNotThrow(() -> useCase.replay(other.id(), dead.id(), otherOwner()));
    }

    private WebhookSubscription subscribeOther() {
        return subscriptions.save(WebhookSubscription.active("wh_0000000000000000009",
                UUID.randomUUID(), "https://other.example.com/hooks",
                Set.of(EventPattern.of("payment.*")), "whsec_0123456789abcdef", START));
    }

    private UUID otherOwner() {
        return subscriptions.findById("wh_0000000000000000009").orElseThrow().principalId();
    }
}
