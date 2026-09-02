package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.DeliveryState;
import com.sharkpay.gateway.domain.EventPattern;
import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.domain.WebhookSignature;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.events.EnvelopeCodec;
import com.sharkpay.gateway.fakes.InMemoryWebhookDeliveryRepository;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.RecordingWebhookSender;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivery-attempt worker — the heart of the webhook dispatcher:
 * HMAC-SHA256 signature headers over the exact payload bytes, 2xx →
 * delivered, failures → the 1m/2m/4m...1h backoff, exactly 8 attempts then
 * dead, auto-pause after 3 consecutive dead deliveries.
 */
class DeliveryAttemptUseCaseTest {

    private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final String SECRET = "whsec_0123456789abcdef";
    private static final String URL = "https://merchant.example.com/hooks";

    private final InMemoryWebhookSubscriptionRepository subscriptions =
            new InMemoryWebhookSubscriptionRepository();
    private final InMemoryWebhookDeliveryRepository deliveries =
            new InMemoryWebhookDeliveryRepository();
    private final RecordingWebhookSender sender = new RecordingWebhookSender();
    private final MutableClock clock = new MutableClock(START);
    private final DeliveryAttemptUseCase worker = new DeliveryAttemptUseCase(deliveries,
            subscriptions, sender, clock);
    private final EnvelopeCodec codec = new EnvelopeCodec(JsonMapper.builder().build());

    private WebhookSubscription subscribe() {
        WebhookSubscription subscription = WebhookSubscription.active("wh_0000000000000000001",
                PRINCIPAL, URL, Set.of(EventPattern.of("payment.*")), SECRET, START);
        return subscriptions.save(subscription);
    }

    private WebhookDelivery newDelivery(WebhookSubscription subscription, String eventId) {
        String payload = "{\"id\":\"" + eventId + "\",\"type\":\"payment.succeeded\"}";
        return deliveries.save(WebhookDelivery.pending("whd_" + eventId, subscription.id(),
                eventId, "payment.succeeded", payload, clock.instant()));
    }

    @Test
    void signsTheExactPayloadBytesWithAllThreeHeaders() {
        WebhookSubscription subscription = subscribe();
        WebhookDelivery delivery = newDelivery(subscription, "0001");
        sender.alwaysDeliver(200);

        worker.processDue(clock.instant());

        assertEquals(1, sender.sendCount());
        RecordingWebhookSender.Sent sent = sender.sends().get(0);
        assertEquals(URL, sent.url());
        assertEquals(delivery.payload(), sent.bodyText());

        String signatureHeader = sent.header("X-SharkPay-Signature");
        assertNotNull(signatureHeader);
        assertTrue(signatureHeader.matches("^t=[0-9]+,v1=[0-9a-f]{64}$"),
                signatureHeader);
        String timestamp = sent.header("X-SharkPay-Timestamp");
        assertNotNull(timestamp);
        assertEquals(String.valueOf(clock.instant().getEpochSecond()), timestamp);
        assertEquals(delivery.id(), sent.header("X-SharkPay-Delivery"));
        assertEquals("application/json", sent.header("Content-Type"));

        // a receiver verifies: v1 == hmac_sha256(t + "." + raw body, secret)
        long t = Long.parseLong(signatureHeader.substring(2, signatureHeader.indexOf(',')));
        String v1 = signatureHeader.substring(signatureHeader.indexOf("v1=") + 3);
        WebhookSignature expected = WebhookSignature.sign(SECRET, t,
                delivery.payload().getBytes(StandardCharsets.UTF_8));
        assertEquals(expected.hex(), v1);
        assertEquals(expected.headerValue(), signatureHeader);
        // header t equals X-SharkPay-Timestamp
        assertEquals(t, Long.parseLong(timestamp));
    }

    @Test
    void anyTwoXxCountsAsDeliveredAndResetTheDeadCounter() {
        WebhookSubscription subscription = subscribe();
        newDelivery(subscription, "0002");
        sender.alwaysDeliver(204);
        worker.processDue(clock.instant());

        WebhookDelivery delivery = deliveries.all().values().iterator().next();
        assertEquals(DeliveryState.DELIVERED, delivery.state());
        assertEquals(1, delivery.attemptCount());
        assertEquals(204, delivery.lastResponseCode());
        assertEquals(clock.instant(), delivery.deliveredAt());
        assertNull(delivery.nextAttemptAt());
        // a delivered delivery is never re-sent
        assertEquals(0, worker.processDue(clock.instant().plus(Duration.ofHours(2))).attempted());
        assertEquals(1, sender.sendCount());
    }

    @Test
    void failuresFollowTheExactBackoffSchedule() {
        WebhookSubscription subscription = subscribe();
        WebhookDelivery delivery = newDelivery(subscription, "0003");
        sender.alwaysReject(500);

        // attempt 1 fails → next in 1m
        worker.processDue(clock.instant());
        delivery = deliveries.findById(delivery.id()).orElseThrow();
        assertEquals(DeliveryState.PENDING, delivery.state());
        assertEquals(1, delivery.attemptCount());
        assertEquals(START.plus(Duration.ofMinutes(1)), delivery.nextAttemptAt());
        assertEquals(500, delivery.lastResponseCode());
        assertEquals("http 500", delivery.lastError());

        // not due before the scheduled instant
        clock.set(START.plus(Duration.ofSeconds(59)));
        assertEquals(0, worker.processDue(clock.instant()).attempted());

        // attempts 2..7: 2m, 4m, 8m, 16m, 32m, 1h (capped)
        long[] waits = {2, 4, 8, 16, 32, 60};
        for (long minutes : waits) {
            clock.set(delivery.nextAttemptAt());
            worker.processDue(clock.instant());
            delivery = deliveries.findById(delivery.id()).orElseThrow();
            assertEquals(DeliveryState.PENDING, delivery.state());
            assertEquals(clock.instant().plus(Duration.ofMinutes(minutes)),
                    delivery.nextAttemptAt(), "after this attempt, next wait is " + minutes + "m");
        }
        // 8th and final failure: dead, no next attempt ever scheduled
        clock.set(delivery.nextAttemptAt());
        worker.processDue(clock.instant());
        delivery = deliveries.findById(delivery.id()).orElseThrow();
        assertEquals(DeliveryState.DEAD, delivery.state());
        assertEquals(8, delivery.attemptCount());
        assertNull(delivery.nextAttemptAt());

        // exactly 8 sends happened — the 9th is never attempted
        clock.advance(Duration.ofHours(3));
        assertEquals(0, worker.processDue(clock.instant()).attempted());
        assertEquals(8, sender.sendCount());
    }

    @Test
    void transportErrorsAreFailedAttemptsWithNoResponseCode() {
        WebhookSubscription subscription = subscribe();
        WebhookDelivery delivery = newDelivery(subscription, "0004");
        sender.alwaysTransportError();

        worker.processDue(clock.instant());
        delivery = deliveries.findById(delivery.id()).orElseThrow();
        assertEquals(DeliveryState.PENDING, delivery.state());
        assertEquals(1, delivery.attemptCount());
        assertNull(delivery.lastResponseCode());
        assertEquals("transport error", delivery.lastError());
        assertEquals(START.plus(Duration.ofMinutes(1)), delivery.nextAttemptAt());
    }

    @Test
    void threeConsecutiveDeadDeliveriesAutoPauseTheSubscription() {
        WebhookSubscription subscription = subscribe();
        killOne("d1");
        killOne("d2");
        // two consecutive dead: counter 2, still active (threshold is 3)
        assertEquals(2, subscriptions.findById(subscription.id()).orElseThrow()
                .consecutiveDeadDeliveries());
        assertEquals(com.sharkpay.gateway.domain.SubscriptionState.ACTIVE,
                subscriptions.findById(subscription.id()).orElseThrow().state());

        // third consecutive dead delivery: auto-paused in DEAD state
        killOne("d3");
        WebhookSubscription after = subscriptions.findById(subscription.id()).orElseThrow();
        assertEquals(com.sharkpay.gateway.domain.SubscriptionState.DEAD, after.state());
        assertEquals(3, after.consecutiveDeadDeliveries());
        // paused endpoints receive no further sends
        assertEquals(3 * 8, sender.sendCount());

        // a later delivery unblocks when the operator resumes
        subscriptions.save(after.resumed(clock.instant()));
        sender.alwaysDeliver(200);
        newDelivery(after, "revive");
        DeliveryAttemptUseCase.Summary revived = worker.processDue(clock.instant());
        assertEquals(1, revived.delivered());
        assertEquals(0, subscriptions.findById(subscription.id()).orElseThrow()
                .consecutiveDeadDeliveries());
    }

    @Test
    void aSuccessBetweenDeathsResetsTheConsecutiveDeadCounter() {
        WebhookSubscription subscription = subscribe();
        // one dead delivery (auto-pause threshold not yet reached: 1 < 3)
        killOne("d1");
        assertEquals(1, subscriptions.findById(subscription.id()).orElseThrow()
                .consecutiveDeadDeliveries());

        // a successful delivery resets the counter
        sender.alwaysDeliver(200);
        newDelivery(subscription, "ok1");
        worker.processDue(clock.instant());
        assertEquals(0, subscriptions.findById(subscription.id()).orElseThrow()
                .consecutiveDeadDeliveries());

        // two more dead deliveries: 1 + 0 + 2 = 2 consecutive, still active
        killOne("d2");
        killOne("d3");
        assertEquals(2, subscriptions.findById(subscription.id()).orElseThrow()
                .consecutiveDeadDeliveries());
        assertEquals(com.sharkpay.gateway.domain.SubscriptionState.ACTIVE,
                subscriptions.findById(subscription.id()).orElseThrow().state());
    }

    private void killOne(String suffix) {
        sender.alwaysReject(500);
        WebhookSubscription subscription = subscriptions.findById(
                "wh_0000000000000000001").orElseThrow();
        WebhookDelivery delivery = newDelivery(subscription, suffix);
        while (deliveries.findById(delivery.id()).orElseThrow().state() == DeliveryState.PENDING) {
            worker.processDue(clock.instant());
            WebhookDelivery current = deliveries.findById(delivery.id()).orElseThrow();
            if (current.state() == DeliveryState.PENDING) {
                clock.set(current.nextAttemptAt());
            }
        }
        assertEquals(DeliveryState.DEAD, deliveries.findById(delivery.id()).orElseThrow().state());
    }

    @Test
    void deliveriesOfMissingOrPausedSubscriptionsAreSkipped() {
        // missing: delivery row whose subscription row vanished
        newDelivery(WebhookSubscription.active("wh_gone0000000000000000001", PRINCIPAL, URL,
                Set.of(EventPattern.of("payment.*")), SECRET, START), "gone");
        worker.processDue(clock.instant());
        assertEquals(0, sender.sendCount());

        // paused: delivery is kept pending (in-flight semantics), not sent
        WebhookSubscription paused = subscribe();
        subscriptions.save(paused.paused(clock.instant()));
        newDelivery(paused, "held");
        DeliveryAttemptUseCase.Summary summary = worker.processDue(clock.instant());
        assertEquals(0, sender.sendCount());
        assertEquals(0, summary.delivered());
        WebhookDelivery held = deliveries.all().values().stream()
                .filter(d -> d.id().endsWith("held")).findFirst().orElseThrow();
        assertEquals(DeliveryState.PENDING, held.state());
        assertEquals(0, held.attemptCount());
    }

    @Test
    void signatureIsDeterministicForTheSameSecretTimestampAndBody() {
        byte[] body = "{\"id\":\"x\",\"type\":\"payment.succeeded\"}".getBytes(
                StandardCharsets.UTF_8);
        WebhookSignature first = WebhookSignature.sign(SECRET, 1767312000L, body);
        WebhookSignature second = WebhookSignature.sign(SECRET, 1767312000L, body);
        assertEquals(first.hex(), second.hex());
        assertEquals(first.headerValue(), second.headerValue());
        // a different secret, timestamp or body changes the signature
        assertFalse(WebhookSignature.sign(SECRET + "x", 1767312000L, body).hex()
                .equals(first.hex()));
        assertFalse(WebhookSignature.sign(SECRET, 1767312001L, body).hex().equals(first.hex()));
        assertFalse(WebhookSignature.sign(SECRET, 1767312000L,
                "{\"id\":\"y\",\"type\":\"payment.succeeded\"}".getBytes(StandardCharsets.UTF_8))
                .hex().equals(first.hex()));
        // the header value is the wire form a receiver parses
        assertEquals("t=1767312000,v1=" + first.hex(), first.headerValue());
    }
}
