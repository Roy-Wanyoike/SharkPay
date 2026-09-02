package com.sharkpay.gateway.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One outbound webhook delivery: the CloudEvents payload destined for a
 * registered endpoint, its at-least-once state machine and retry bookkeeping.
 *
 * <p>Delivery idempotency is (subscription, event id): the dispatcher never
 * creates a second delivery for an event a subscription already holds, and
 * a {@code delivered} delivery is never re-sent (receiver-side dedup on
 * {@code event.id} is documented in the service README — at-least-once
 * semantics).</p>
 *
 * @param id               {@code whd_...}
 * @param subscriptionId   the endpoint this delivery belongs to
 * @param eventId          CloudEvent id (dedupe key)
 * @param eventType        unversioned catalog event type ({@code payment.succeeded})
 * @param payload          the exact JSON bytes POSTed to the endpoint (signed)
 * @param state            pending → delivered | dead
 * @param attemptCount     send attempts made so far (≤ {@value BackoffPolicy#MAX_ATTEMPTS})
 * @param nextAttemptAt    when the worker may next try (pending only)
 * @param lastResponseCode HTTP status of the last attempt (null on network error)
 * @param lastError        short transport error description of the last attempt
 * @param createdAt        audit
 * @param deliveredAt      set on the first 2xx response
 */
public record WebhookDelivery(String id, String subscriptionId, String eventId, String eventType,
                              String payload, DeliveryState state, int attemptCount,
                              Instant nextAttemptAt, Integer lastResponseCode, String lastError,
                              Instant createdAt, Instant deliveredAt) {

    public WebhookDelivery {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(subscriptionId, "subscriptionId is required");
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(payload, "payload is required");
        Objects.requireNonNull(state, "state is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (attemptCount < 0 || attemptCount > BackoffPolicy.MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attemptCount must be within 0.."
                    + BackoffPolicy.MAX_ATTEMPTS);
        }
        if (state == DeliveryState.PENDING && nextAttemptAt == null) {
            throw new IllegalArgumentException("a pending delivery requires nextAttemptAt");
        }
    }

    /** A fresh delivery: pending, zero attempts, immediately due. */
    public static WebhookDelivery pending(String id, String subscriptionId, String eventId,
                                          String eventType, String payload, Instant now) {
        return new WebhookDelivery(id, subscriptionId, eventId, eventType, payload,
                DeliveryState.PENDING, 0, now, null, null, now, null);
    }

    /** The delivery succeeded (any 2xx counts, webhooks.yaml). */
    public WebhookDelivery succeeded(int responseCode, Instant now) {
        if (state != DeliveryState.PENDING) {
            throw new IllegalStateException("only pending deliveries can be delivered: " + state);
        }
        return new WebhookDelivery(id, subscriptionId, eventId, eventType, payload,
                DeliveryState.DELIVERED, attemptCount + 1, null, responseCode, null, createdAt, now);
    }

    /**
     * The attempt failed: attempt count grows, the next attempt is scheduled
     * by the backoff policy — or, after the {@value BackoffPolicy#MAX_ATTEMPTS}th
     * failure, the delivery is dead.
     */
    public WebhookDelivery attemptFailed(Integer responseCode, String error, Instant now) {
        if (state != DeliveryState.PENDING) {
            throw new IllegalStateException("only pending deliveries can be retried: " + state);
        }
        int attempts = attemptCount + 1;
        if (BackoffPolicy.exhausted(attempts)) {
            return new WebhookDelivery(id, subscriptionId, eventId, eventType, payload,
                    DeliveryState.DEAD, attempts, null, responseCode, error, createdAt, null);
        }
        return new WebhookDelivery(id, subscriptionId, eventId, eventType, payload,
                DeliveryState.PENDING, attempts, now.plus(BackoffPolicy.delayBeforeAttempt(attempts)),
                responseCode, error, createdAt, null);
    }

    /**
     * Operator replay: only dead deliveries can be re-queued; the attempt
     * counter restarts and the delivery is immediately due.
     */
    public WebhookDelivery replayed(Instant now) {
        if (state != DeliveryState.DEAD) {
            throw new DeliveryNotReplayableException(id, state);
        }
        return new WebhookDelivery(id, subscriptionId, eventId, eventType, payload,
                DeliveryState.PENDING, 0, now, lastResponseCode, lastError, createdAt, null);
    }

    /** Whether the worker may attempt the send now. */
    public boolean dueAt(Instant now) {
        return state == DeliveryState.PENDING && !nextAttemptAt.isAfter(now);
    }
}
