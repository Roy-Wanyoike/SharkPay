package com.sharkpay.gateway.config;

import com.sharkpay.gateway.fakes.InMemoryWebhookDeliveryRepository;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.RecordingWebhookSender;
import com.sharkpay.gateway.service.DeliveryAttemptUseCase;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The delivery sweeper: fixed-cadence drain of due deliveries, driven by
 * the injected clock (the @Scheduled annotation is pinned here — the
 * cadence itself is Spring's business at runtime, not the unit's).
 */
class WebhookDeliverySweeperTest {

    private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

    private final InMemoryWebhookSubscriptionRepository subscriptions =
            new InMemoryWebhookSubscriptionRepository();
    private final InMemoryWebhookDeliveryRepository deliveries =
            new InMemoryWebhookDeliveryRepository();
    private final RecordingWebhookSender sender = new RecordingWebhookSender();
    private final MutableClock clock = new MutableClock(START);
    private final DeliveryAttemptUseCase worker =
            new DeliveryAttemptUseCase(deliveries, subscriptions, sender, clock);
    private final WebhookDeliverySweeper sweeper = new WebhookDeliverySweeper(worker, clock);

    @Test
    void sweepDrainsDueDeliveriesAtTheCurrentClock() {
        // nothing due: the sweep is a silent no-op heartbeat
        assertDoesNotThrow(() -> sweeper.sweep());
        assertEquals(0, sender.sendCount());

        // a pending delivery for an active subscription...
        com.sharkpay.gateway.domain.WebhookSubscription subscription =
                com.sharkpay.gateway.domain.WebhookSubscription.active("wh_0000000000000000001",
                        java.util.UUID.randomUUID(), "https://merchant.example.com/hooks",
                        java.util.Set.of(com.sharkpay.gateway.domain.EventPattern.of("payment.*")),
                        "whsec_0123456789abcdef", START);
        subscriptions.save(subscription);
        deliveries.save(com.sharkpay.gateway.domain.WebhookDelivery.pending("whd_00000000000000001",
                subscription.id(), java.util.UUID.randomUUID().toString(),
                "payment.succeeded", "{}", clock.instant()));

        // ...but not yet due: advance past the due instant
        clock.advance(Duration.ofSeconds(1));
        assertDoesNotThrow(() -> sweeper.sweep());
        assertEquals(1, sender.sendCount());
        assertEquals(1, deliveries.all().values().iterator().next().attemptCount());
    }

    @Test
    void theScheduledAnnotationPinsTheConfiguredCadence() throws Exception {
        // gateway.webhook.sweep-ms (default 30 s) drives the fixedDelay — the
        // annotation must exist and read the property so ops can tune it
        java.lang.reflect.Method sweep = WebhookDeliverySweeper.class.getMethod("sweep");
        org.springframework.scheduling.annotation.Scheduled scheduled =
                sweep.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertEquals("${gateway.webhook.sweep-ms:30000}", scheduled.fixedDelayString());
    }
}
