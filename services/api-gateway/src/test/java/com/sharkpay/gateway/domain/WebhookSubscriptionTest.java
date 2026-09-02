package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Webhook endpoint domain rules: https-only, glob subscription, auto-pause. */
class WebhookSubscriptionTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final String SECRET = "whsec_test_signing_secret_0123456789";

    private static WebhookSubscription active() {
        return WebhookSubscription.active("wh_000000000000000000000001", PRINCIPAL,
                "https://merchant.example.com/sharkpay/webhooks",
                Set.of(EventPattern.of("payment.*")), SECRET, NOW);
    }

    @Test
    void httpsOnlyEndpointsAreEnforced() {
        HttpsUrlRequiredException error = assertThrows(HttpsUrlRequiredException.class,
                () -> WebhookSubscription.active("wh_x", PRINCIPAL,
                        "http://merchant.example.com/webhooks",
                        Set.of(EventPattern.of("payment.*")), SECRET, NOW));
        assertEquals("http://merchant.example.com/webhooks", error.url());
        assertThrows(HttpsUrlRequiredException.class, () -> WebhookSubscription.active("wh_x",
                PRINCIPAL, "ftp://merchant.example.com/webhooks",
                Set.of(EventPattern.of("payment.*")), SECRET, NOW));
    }

    @Test
    void secretLengthBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> WebhookSubscription.active("wh_x",
                PRINCIPAL, "https://x.example.com", Set.of(EventPattern.of("payment.*")),
                "whsec_short", NOW));
        assertThrows(IllegalArgumentException.class, () -> WebhookSubscription.active("wh_x",
                PRINCIPAL, "https://x.example.com", Set.of(EventPattern.of("payment.*")),
                "x".repeat(257), NOW));
    }

    @Test
    void atLeastOneEventPatternIsRequired() {
        assertThrows(InvalidEventTypesException.class, () -> WebhookSubscription.active("wh_x",
                PRINCIPAL, "https://x.example.com", Set.of(), SECRET, NOW));
    }

    @Test
    void matchesEventConsultsAllPatterns() {
        WebhookSubscription subscription = WebhookSubscription.active("wh_x", PRINCIPAL,
                "https://x.example.com",
                Set.of(EventPattern.of("payment.succeeded"), EventPattern.of("payout.*")),
                SECRET, NOW);
        assertTrue(subscription.matchesEvent("payment.succeeded"));
        assertTrue(subscription.matchesEvent("payout.sent"));
        assertFalse(subscription.matchesEvent("payment.created"));
        assertFalse(subscription.matchesEvent("wallet.balance.changed"));
    }

    @Test
    void pauseStopsEventAcceptanceResumeRestoresIt() {
        WebhookSubscription paused = active().paused(NOW.plusSeconds(1));
        assertEquals(SubscriptionState.PAUSED, paused.state());
        assertFalse(paused.state().acceptsEvents());

        WebhookSubscription resumed = paused.resumed(NOW.plusSeconds(2));
        assertEquals(SubscriptionState.ACTIVE, resumed.state());
        assertTrue(resumed.state().acceptsEvents());
    }

    @Test
    void consecutiveDeadDeliveriesAutoPauseAtThreshold() {
        WebhookSubscription subscription = active();
        int dead = 0;
        WebhookSubscription.WebhookDeliveryOutcome outcome = null;
        while (true) {
            outcome = subscription.recordDeadDelivery(NOW);
            dead++;
            subscription = outcome.subscription();
            if (outcome.autoPaused()) {
                break;
            }
            assertEquals(dead, subscription.consecutiveDeadDeliveries());
            assertTrue(dead < WebhookSubscription.AUTO_PAUSE_THRESHOLD,
                    "auto-pause must trigger at the threshold");
        }
        assertEquals(WebhookSubscription.AUTO_PAUSE_THRESHOLD, dead);
        assertEquals(SubscriptionState.DEAD, subscription.state());
        assertFalse(subscription.state().acceptsEvents());
    }

    @Test
    void autoPauseThresholdIsThree() {
        assertEquals(3, WebhookSubscription.AUTO_PAUSE_THRESHOLD);
    }

    @Test
    void deliveredDeliveryResetsTheDeadCounter() {
        WebhookSubscription subscription = active().recordDeadDelivery(NOW).subscription();
        assertEquals(1, subscription.consecutiveDeadDeliveries());
        subscription = subscription.recordDeadDelivery(NOW).subscription();
        assertEquals(2, subscription.consecutiveDeadDeliveries());

        subscription = subscription.recordDelivered(NOW);
        assertEquals(0, subscription.consecutiveDeadDeliveries());
        assertEquals(SubscriptionState.ACTIVE, subscription.state());

        // two more dead deliveries are not enough after a success
        subscription = subscription.recordDeadDelivery(NOW).subscription()
                .recordDeadDelivery(NOW).subscription();
        assertEquals(2, subscription.consecutiveDeadDeliveries());
        assertEquals(SubscriptionState.ACTIVE, subscription.state());
    }

    @Test
    void resumeResetsTheDeadCounter() {
        WebhookSubscription dead = active();
        for (int i = 0; i < WebhookSubscription.AUTO_PAUSE_THRESHOLD; i++) {
            dead = dead.recordDeadDelivery(NOW).subscription();
        }
        assertEquals(SubscriptionState.DEAD, dead.state());
        WebhookSubscription resumed = dead.resumed(NOW.plusSeconds(1));
        assertEquals(SubscriptionState.ACTIVE, resumed.state());
        assertEquals(0, resumed.consecutiveDeadDeliveries());
    }

    @Test
    void deletedEndpointsStopAcceptingEvents() {
        WebhookSubscription deleted = active().deleted(NOW.plusSeconds(1));
        assertEquals(SubscriptionState.DELETED, deleted.state());
        assertFalse(deleted.state().acceptsEvents());
    }

    @Test
    void negativeDeadCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookSubscription("wh_x",
                PRINCIPAL, "https://x.example.com", Set.of(EventPattern.of("payment.*")), SECRET,
                SubscriptionState.ACTIVE, -1, NOW, NOW));
    }
}
