package com.sharkpay.gateway.api;

import com.sharkpay.gateway.api.dto.EventAcceptedJson;
import com.sharkpay.gateway.api.dto.WebhookEndpointCreateRequest;
import com.sharkpay.gateway.api.dto.WebhookEndpointJson;
import com.sharkpay.gateway.api.dto.WebhookEndpointListJson;
import com.sharkpay.gateway.api.dto.WebhookDeliveryJson;
import com.sharkpay.gateway.api.dto.WebhookDeliveryListJson;
import com.sharkpay.gateway.api.dto.ReplayAcceptedJson;
import com.sharkpay.gateway.domain.DeliveryState;
import com.sharkpay.gateway.domain.IdempotencyConflictException;
import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.ports.IdempotencyCache;
import com.sharkpay.gateway.ports.WebhookSubscriptionRepository;
import com.sharkpay.gateway.service.CreateWebhookSubscriptionUseCase;
import com.sharkpay.gateway.service.Ids;
import com.sharkpay.gateway.service.WebhookDeliveryUseCase;
import com.sharkpay.gateway.service.WebhookSubscriptionLifecycleUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhook endpoint management implementing
 * contracts/openapi/v1/webhooks.yaml (createWebhookEndpoint,
 * listWebhookEndpoints, getWebhookEndpoint, deleteWebhookEndpoint —
 * required scope {@code webhooks:manage}) plus the gateway's delivery-log
 * extensions (pause/resume, list deliveries, replay dead deliveries).
 *
 * <p>Idempotency: the create requires an {@code Idempotency-Key}; the cache
 * stores the created endpoint id only. Pause/resume/delete are naturally
 * idempotent state toggles; replay is an explicit at-least-once operator
 * action (dedup is the receiver's contract, see README).</p>
 */
@RestController
public final class WebhookEndpointController {

    static final String CREATE_SCOPE = "CREATE_WEBHOOK_ENDPOINT";

    private final CreateWebhookSubscriptionUseCase create;
    private final WebhookSubscriptionLifecycleUseCase lifecycle;
    private final WebhookDeliveryUseCase deliveries;
    private final IdempotencyCache idempotency;
    private final WebhookSubscriptionRepository subscriptions;

    public WebhookEndpointController(CreateWebhookSubscriptionUseCase create,
                                     WebhookSubscriptionLifecycleUseCase lifecycle,
                                     WebhookDeliveryUseCase deliveries,
                                     IdempotencyCache idempotency,
                                     WebhookSubscriptionRepository subscriptions) {
        this.create = create;
        this.lifecycle = lifecycle;
        this.deliveries = deliveries;
        this.idempotency = idempotency;
        this.subscriptions = subscriptions;
    }

    /** createWebhookEndpoint: the secret is returned in full only here. */
    @PostMapping("/v1/webhook-endpoints")
    public ResponseEntity<WebhookEndpointJson> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WebhookEndpointCreateRequest body,
            HttpServletRequest request) {
        UUID principal = AuthenticatedRequest.principal(request);
        String key = idempotencyKey.trim();
        String fingerprint = principal + "|" + body;
        Optional<IdempotencyCache.CachedResponse> stored = idempotency.find(CREATE_SCOPE, key);
        if (stored.isPresent()) {
            if (!stored.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(key);
            }
            WebhookSubscription original = subscriptions.findById(stored.get().entityId())
                    .orElseThrow(() -> new NoSuchElementException("webhook endpoint "
                            + stored.get().entityId() + " referenced by idempotency key "
                            + key + " is missing"));
            return ResponseEntity.status(201)
                    .header("X-Request-Id", Ids.requestId())
                    .header("X-Idempotent-Replay", "true")
                    .body(WebhookEndpointJson.redacted(original));
        }
        WebhookSubscription subscription = create.create(principal, body.url(), body.events(),
                body.secret());
        idempotency.put(CREATE_SCOPE, key,
                IdempotencyCache.CachedResponse.entity(fingerprint, 201, subscription.id()));
        return ResponseEntity.status(201)
                .header("X-Request-Id", Ids.requestId())
                .body(WebhookEndpointJson.withSecret(subscription));
    }

    /** listWebhookEndpoints (cursor-paginated, redacted secrets). */
    @GetMapping("/v1/webhook-endpoints")
    public WebhookEndpointListJson list(HttpServletRequest request,
                                        @RequestParam(required = false) Integer limit,
                                        @RequestParam(required = false) String cursor) {
        int pageSize = sanitizedLimit(limit);
        List<WebhookSubscription> page = lifecycle.list(
                AuthenticatedRequest.principal(request), pageSize, cursor);
        String nextCursor = page.size() == pageSize ? page.get(page.size() - 1).id() : null;
        return new WebhookEndpointListJson(
                page.stream().map(WebhookEndpointJson::redacted).toList(), nextCursor);
    }

    /** getWebhookEndpoint (secret redacted). */
    @GetMapping("/v1/webhook-endpoints/{id}")
    public WebhookEndpointJson get(@PathVariable("id") String id, HttpServletRequest request) {
        return WebhookEndpointJson.redacted(
                lifecycle.get(id, AuthenticatedRequest.principal(request)));
    }

    /** deleteWebhookEndpoint: in-flight deliveries complete, then gone. */
    @DeleteMapping("/v1/webhook-endpoints/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id, HttpServletRequest request) {
        lifecycle.delete(id, AuthenticatedRequest.principal(request));
        return ResponseEntity.noContent().header("X-Request-Id", Ids.requestId()).build();
    }

    /** Extension: operator pause (stops new deliveries). */
    @PostMapping("/v1/webhook-endpoints/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable("id") String id, HttpServletRequest request) {
        lifecycle.pause(id, AuthenticatedRequest.principal(request));
        return ResponseEntity.noContent().header("X-Request-Id", Ids.requestId()).build();
    }

    /** Extension: operator resume (resets the consecutive-dead counter). */
    @PostMapping("/v1/webhook-endpoints/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable("id") String id, HttpServletRequest request) {
        lifecycle.resume(id, AuthenticatedRequest.principal(request));
        return ResponseEntity.noContent().header("X-Request-Id", Ids.requestId()).build();
    }

    /** Extension: the delivery log of one endpoint, newest first. */
    @GetMapping("/v1/webhook-endpoints/{id}/deliveries")
    public WebhookDeliveryListJson listDeliveries(@PathVariable("id") String id,
                                                  HttpServletRequest request,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) String cursor) {
        int pageSize = sanitizedLimit(limit);
        List<WebhookDelivery> page = deliveries.list(id, AuthenticatedRequest.principal(request),
                pageSize, cursor);
        String nextCursor = page.size() == pageSize ? page.get(page.size() - 1).id() : null;
        return new WebhookDeliveryListJson(page.stream().map(WebhookDeliveryJson::of).toList(),
                nextCursor);
    }

    /**
     * Extension: operator replay — re-queues a dead delivery. Only dead
     * deliveries (409 for pending/delivered); at-least-once semantics, the
     * receiver dedupes on {@code X-SharkPay-Delivery} / {@code event.id}.
     */
    @PostMapping("/v1/webhook-endpoints/{id}/deliveries/{deliveryId}/replay")
    public ResponseEntity<ReplayAcceptedJson> replay(@PathVariable("id") String id,
                                                     @PathVariable("deliveryId") String deliveryId,
                                                     HttpServletRequest request) {
        WebhookDelivery replayed = deliveries.replay(id, deliveryId,
                AuthenticatedRequest.principal(request));
        return ResponseEntity.accepted()
                .header("X-Request-Id", Ids.requestId())
                .body(new ReplayAcceptedJson(replayed.id(), DeliveryState.PENDING.wireName()));
    }

    private static int sanitizedLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return limit;
    }
}
