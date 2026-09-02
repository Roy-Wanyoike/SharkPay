package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.domain.WebhookSignature;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.ports.WebhookDeliveryRepository;
import com.sharkpay.gateway.ports.WebhookSender;
import com.sharkpay.gateway.ports.WebhookSubscriptionRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The delivery attempt worker: picks up due pending deliveries, signs the
 * exact payload bytes with the endpoint secret (HMAC-SHA256,
 * {@code t=<unix>,v1=<hex>}), POSTs them with the
 * {@code X-SharkPay-Signature}/{@code X-SharkPay-Timestamp}/
 * {@code X-SharkPay-Delivery} headers, and advances the state machine:
 * any 2xx → delivered; failure → backoff-scheduled retry; after the 8th
 * failed attempt → dead, and after three consecutive dead deliveries the
 * subscription auto-pauses.
 */
public final class DeliveryAttemptUseCase {

    /** Maximum deliveries processed per sweep. */
    public static final int BATCH_SIZE = 50;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookSender sender;
    private final Clock clock;

    public DeliveryAttemptUseCase(WebhookDeliveryRepository deliveries,
                                  WebhookSubscriptionRepository subscriptions,
                                  WebhookSender sender, Clock clock) {
        this.deliveries = Objects.requireNonNull(deliveries, "deliveryRepository is required");
        this.subscriptions = Objects.requireNonNull(subscriptions,
                "webhookSubscriptionRepository is required");
        this.sender = Objects.requireNonNull(sender, "webhookSender is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Processes every due pending delivery (oldest first).
     *
     * @return the sweep summary (attempted, delivered, dead, auto-paused)
     */
    public Summary processDue(Instant now) {
        Objects.requireNonNull(now, "now is required");
        List<WebhookDelivery> due = deliveries.findDue(now, BATCH_SIZE);
        int delivered = 0;
        int dead = 0;
        int autoPaused = 0;
        for (WebhookDelivery delivery : due) {
            WebhookSubscription subscription = subscriptions.findById(delivery.subscriptionId())
                    .orElse(null);
            if (subscription == null) {
                continue;
            }
            if (subscription.state() == com.sharkpay.gateway.domain.SubscriptionState.PAUSED) {
                continue;
            }
            byte[] body = delivery.payload().getBytes(StandardCharsets.UTF_8);
            WebhookSignature signature = WebhookSignature.sign(subscription.signingSecret(),
                    now.getEpochSecond(), body);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("X-SharkPay-Signature", signature.headerValue());
            headers.put("X-SharkPay-Timestamp", String.valueOf(signature.timestamp()));
            headers.put("X-SharkPay-Delivery", delivery.id());
            WebhookSender.SendResult result = sender.send(subscription.url(), body, headers);

            if (result.delivered()) {
                deliveries.save(delivery.succeeded(result.statusCode(), now));
                subscriptions.save(subscription.recordDelivered(now));
                delivered++;
            } else {
                WebhookDelivery after = delivery.attemptFailed(
                        result.statusCode() == WebhookSender.SendResult.NO_RESPONSE
                                ? null : result.statusCode(),
                        errorText(result), now);
                deliveries.save(after);
                if (after.state() == com.sharkpay.gateway.domain.DeliveryState.DEAD) {
                    dead++;
                    WebhookSubscription.WebhookDeliveryOutcome outcome =
                            subscription.recordDeadDelivery(now);
                    subscriptions.save(outcome.subscription());
                    if (outcome.autoPaused()) {
                        autoPaused++;
                    }
                }
            }
        }
        return new Summary(due.size(), delivered, dead, autoPaused);
    }

    private static String errorText(WebhookSender.SendResult result) {
        return result.statusCode() == WebhookSender.SendResult.NO_RESPONSE
                ? "transport error"
                : "http " + result.statusCode();
    }

    /** One sweep's counters. */
    public record Summary(int attempted, int delivered, int dead, int autoPaused) {
    }
}
