package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.SubscriptionState;
import com.sharkpay.gateway.domain.SubscriptionStateException;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Endpoint lifecycle: get/list/pause/resume/soft-delete, scoped to owner. */
class WebhookSubscriptionLifecycleUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();

    private final InMemoryWebhookSubscriptionRepository subscriptions =
            new InMemoryWebhookSubscriptionRepository();
    private final MutableClock clock = new MutableClock(NOW);
    private final CreateWebhookSubscriptionUseCase create =
            new CreateWebhookSubscriptionUseCase(subscriptions, new SequentialRandomness(), clock);
    private final WebhookSubscriptionLifecycleUseCase lifecycle =
            new WebhookSubscriptionLifecycleUseCase(subscriptions, clock);

    private com.sharkpay.gateway.domain.WebhookSubscription seed() {
        return create.create(PRINCIPAL, "https://merchant.example.com/hooks",
                List.of("payment.*"), "whsec_0123456789abcdef");
    }

    @Test
    void pauseStopsNewDeliveriesResumeReactivates() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = seed();

        lifecycle.pause(subscription.id(), PRINCIPAL);
        assertEquals(SubscriptionState.PAUSED, subscriptions.findById(subscription.id())
                .orElseThrow().state());
        org.junit.jupiter.api.Assertions.assertFalse(subscriptions.findById(subscription.id())
                .orElseThrow().state().acceptsEvents());

        lifecycle.resume(subscription.id(), PRINCIPAL);
        assertEquals(SubscriptionState.ACTIVE, subscriptions.findById(subscription.id())
                .orElseThrow().state());
    }

    @Test
    void resumeResetsTheConsecutiveDeadCounterAndReactivatesDeadEndpoints() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = seed();
        // drive it into the auto-paused DEAD state (3 consecutive dead)
        com.sharkpay.gateway.domain.WebhookSubscription walked = subscription;
        for (int i = 0; i < 3; i++) {
            walked = subscriptions.save(walked.recordDeadDelivery(NOW.plusSeconds(i))
                    .subscription());
        }
        assertEquals(SubscriptionState.DEAD, subscriptions.findById(subscription.id())
                .orElseThrow().state());

        lifecycle.resume(subscription.id(), PRINCIPAL);
        com.sharkpay.gateway.domain.WebhookSubscription resumed = subscriptions.findById(
                subscription.id()).orElseThrow();
        assertEquals(SubscriptionState.ACTIVE, resumed.state());
        assertEquals(0, resumed.consecutiveDeadDeliveries());
    }

    @Test
    void pausingAnAutoPausedEndpointIsAConflictButResumingIsFine() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = seed();
        com.sharkpay.gateway.domain.WebhookSubscription dead = subscription;
        for (int i = 0; i < 3; i++) {
            dead = subscriptions.save(dead.recordDeadDelivery(NOW).subscription());
        }
        assertThrows(SubscriptionStateException.class,
                () -> lifecycle.pause(subscription.id(), PRINCIPAL));
        assertDoesNotThrow(() -> lifecycle.resume(subscription.id(), PRINCIPAL));
    }

    @Test
    void softDeleteHidesTheEndpointButKeepsTheRow() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = seed();
        lifecycle.delete(subscription.id(), PRINCIPAL);

        assertEquals(SubscriptionState.DELETED, subscriptions.findById(subscription.id())
                .orElseThrow().state());
        // hidden from listings and lookups (404)...
        assertTrue(lifecycle.list(PRINCIPAL, 50, null).isEmpty());
        assertThrows(NoSuchElementException.class,
                () -> lifecycle.get(subscription.id(), PRINCIPAL));
        // ...but the row survives so in-flight deliveries can complete
        assertTrue(subscriptions.all().containsKey(subscription.id()));
        // and the worker can still resolve the signing secret
        assertTrue(subscriptions.findById(subscription.id()).isPresent());
    }

    @Test
    void resumeOfADeletedEndpointLooksMissing404NotAConflict() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = seed();
        lifecycle.delete(subscription.id(), PRINCIPAL);
        // deleted endpoints are invisible (404) — resuming one does not leak
        // that it ever existed, consistent with get/pause/delete on deleted
        assertThrows(NoSuchElementException.class,
                () -> lifecycle.resume(subscription.id(), PRINCIPAL));
    }

    @Test
    void foreignEndpointsAreIndistinguishableFromMissingOnes() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = seed();
        UUID other = UUID.randomUUID();
        assertThrows(NoSuchElementException.class, () -> lifecycle.get(subscription.id(), other));
        assertThrows(NoSuchElementException.class, () -> lifecycle.pause(subscription.id(), other));
        assertThrows(NoSuchElementException.class,
                () -> lifecycle.delete(subscription.id(), other));
        assertThrows(NoSuchElementException.class,
                () -> lifecycle.get("wh_missing0000000000000000000", PRINCIPAL));
    }

    @Test
    void listIsScopedPaginatedAndIdOrdered() {
        UUID other = UUID.randomUUID();
        com.sharkpay.gateway.domain.WebhookSubscription first = seed();
        com.sharkpay.gateway.domain.WebhookSubscription second = create.create(PRINCIPAL,
                "https://other.example.com/hooks", List.of("payout.*"),
                "whsec_0123456789abcdef");
        create.create(other, "https://foreign.example.com/hooks", List.of("payment.created"),
                "whsec_0123456789abcdef");

        List<com.sharkpay.gateway.domain.WebhookSubscription> page = lifecycle.list(PRINCIPAL,
                50, null);
        assertEquals(2, page.size());
        assertTrue(page.get(0).id().compareTo(page.get(1).id()) < 0);
        assertTrue(page.stream().allMatch(endpoint -> endpoint.principalId().equals(PRINCIPAL)));

        List<com.sharkpay.gateway.domain.WebhookSubscription> paged = lifecycle.list(PRINCIPAL,
                1, first.id());
        assertEquals(1, paged.size());
        assertEquals(second.id(), paged.get(0).id());
    }

    @Test
    void timestampsAdvanceOnTransitions() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = seed();
        clock.advance(Duration.ofMinutes(5));
        lifecycle.pause(subscription.id(), PRINCIPAL);
        com.sharkpay.gateway.domain.WebhookSubscription paused = subscriptions.findById(
                subscription.id()).orElseThrow();
        assertTrue(paused.updatedAt().isAfter(paused.createdAt()));
    }
}
