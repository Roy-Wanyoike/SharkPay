package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The delivery state machine: pending → delivered | dead, replay only dead. */
class WebhookDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    private static WebhookDelivery pending() {
        return WebhookDelivery.pending("whd_000000000000000000000001",
                "wh_000000000000000000000001", "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                "payment.succeeded", "{\"id\":\"x\"}", NOW);
    }

    @Test
    void freshDeliveryIsPendingImmediatelyDue() {
        WebhookDelivery delivery = pending();
        assertEquals(DeliveryState.PENDING, delivery.state());
        assertEquals(0, delivery.attemptCount());
        assertTrue(delivery.dueAt(NOW));
        assertTrue(delivery.dueAt(NOW.plusSeconds(1)));
        assertFalse(delivery.dueAt(NOW.minusNanos(1)));
    }

    @Test
    void successMarksDeliveredWithResponseCodeAndTime() {
        WebhookDelivery delivered = pending().succeeded(204, NOW.plusSeconds(2));
        assertEquals(DeliveryState.DELIVERED, delivered.state());
        assertEquals(1, delivered.attemptCount());
        assertEquals(204, delivered.lastResponseCode());
        assertEquals(NOW.plusSeconds(2), delivered.deliveredAt());
        assertNull(delivered.nextAttemptAt());
        assertFalse(delivered.dueAt(NOW));
    }

    @Test
    void anyFailedAttemptSchedulesBackoffRetry() {
        WebhookDelivery retried = pending().attemptFailed(500, "http 500", NOW);
        assertEquals(DeliveryState.PENDING, retried.state());
        assertEquals(1, retried.attemptCount());
        assertEquals(500, retried.lastResponseCode());
        assertEquals("http 500", retried.lastError());
        // first retry waits exactly one minute
        assertEquals(NOW.plusSeconds(60), retried.nextAttemptAt());
        assertTrue(retried.dueAt(NOW.plusSeconds(60)));
        assertFalse(retried.dueAt(NOW.plusSeconds(59)));
    }

    @Test
    void transportErrorsRecordNullResponseCode() {
        WebhookDelivery retried = pending().attemptFailed(null, "transport error", NOW);
        assertNull(retried.lastResponseCode());
        assertEquals("transport error", retried.lastError());
        assertEquals(DeliveryState.PENDING, retried.state());
    }

    @Test
    void eighthFailureDeadLettersAndNinthIsNeverScheduled() {
        WebhookDelivery delivery = pending();
        Instant now = NOW;
        for (int attempt = 1; attempt <= 8; attempt++) {
            delivery = delivery.attemptFailed(500, "http 500", now);
            if (attempt < 8) {
                assertEquals(DeliveryState.PENDING, delivery.state(),
                        "attempt " + attempt + " must stay pending");
                now = delivery.nextAttemptAt();
            }
        }
        assertEquals(DeliveryState.DEAD, delivery.state());
        assertEquals(8, delivery.attemptCount());
        assertNull(delivery.nextAttemptAt());
        // a dead delivery is never due
        assertFalse(delivery.dueAt(now.plus(java.time.Duration.ofHours(2))));
    }

    @Test
    void retryDelaysFollowTheBackoffSchedule() {
        WebhookDelivery delivery = pending();
        Instant now = NOW;
        java.time.Duration expected = java.time.Duration.ofMinutes(1);
        for (int attempt = 1; attempt <= 7; attempt++) {
            delivery = delivery.attemptFailed(500, "http 500", now);
            assertEquals(now.plus(expected), delivery.nextAttemptAt());
            now = delivery.nextAttemptAt();
            expected = expected.multipliedBy(2).compareTo(BackoffPolicy.CAP) > 0
                    ? BackoffPolicy.CAP : expected.multipliedBy(2);
        }
    }

    @Test
    void replayRestartsDeadDeliveries() {
        WebhookDelivery delivery = pending();
        for (int attempt = 1; attempt <= 8; attempt++) {
            delivery = delivery.attemptFailed(502, "http 502", NOW);
        }
        assertEquals(DeliveryState.DEAD, delivery.state());

        Instant replayAt = NOW.plusSeconds(5_000);
        WebhookDelivery replayed = delivery.replayed(replayAt);
        assertEquals(DeliveryState.PENDING, replayed.state());
        assertEquals(0, replayed.attemptCount());
        assertEquals(replayAt, replayed.nextAttemptAt());
        assertEquals(502, replayed.lastResponseCode());
        assertTrue(replayed.dueAt(replayAt));
    }

    @Test
    void replayRejectsPendingAndDeliveredDeliveries() {
        DeliveryNotReplayableException pendingError = assertThrows(
                DeliveryNotReplayableException.class, () -> pending().replayed(NOW));
        assertEquals(DeliveryState.PENDING, pendingError.state());

        DeliveryNotReplayableException deliveredError = assertThrows(
                DeliveryNotReplayableException.class,
                () -> pending().succeeded(200, NOW).replayed(NOW));
        assertEquals(DeliveryState.DELIVERED, deliveredError.state());
    }

    @Test
    void deliveredDeliveriesCannotBeRetried() {
        WebhookDelivery delivered = pending().succeeded(200, NOW);
        assertThrows(IllegalStateException.class,
                () -> delivered.attemptFailed(500, "http 500", NOW));
        assertThrows(IllegalStateException.class, () -> delivered.succeeded(200, NOW));
    }

    @Test
    void attemptCountIsBounded() {
        // the state machine can never exceed 8; a corrupted row with more is rejected
        assertThrows(IllegalArgumentException.class, () -> new WebhookDelivery(
                "whd_x", "wh_x", "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d", "payment.succeeded",
                "{}", DeliveryState.PENDING, 9, NOW, null, null, NOW, null));
    }
}
