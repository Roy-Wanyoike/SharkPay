package com.sharkpay.gateway.config;

import com.sharkpay.gateway.fakes.InMemoryApiKeyRepository;
import com.sharkpay.gateway.fakes.InMemoryIdempotencyCache;
import com.sharkpay.gateway.fakes.InMemoryQuotaStore;
import com.sharkpay.gateway.fakes.InMemoryWebhookDeliveryRepository;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.RecordingWebhookSender;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.ports.EventSource;
import com.sharkpay.gateway.ports.UpstreamPort;
import com.sharkpay.gateway.ports.WebhookSender;
import com.sharkpay.gateway.service.ApiKeyAdminUseCase;
import com.sharkpay.gateway.service.CreateApiKeyUseCase;
import com.sharkpay.gateway.service.CreateWebhookSubscriptionUseCase;
import com.sharkpay.gateway.service.DeliveryAttemptUseCase;
import com.sharkpay.gateway.service.DispatchEventUseCase;
import com.sharkpay.gateway.service.PassthroughService;
import com.sharkpay.gateway.service.RotateApiKeyUseCase;
import com.sharkpay.gateway.service.SandboxPaymentService;
import com.sharkpay.gateway.service.WebhookDeliveryUseCase;
import com.sharkpay.gateway.service.WebhookSubscriptionLifecycleUseCase;
import com.sharkpay.gateway.testsupport.MutableClock;
import com.sharkpay.gateway.webhook.JdkHttpClientWebhookSender;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the production {@link GatewayConfig} bean factories without a
 * Spring context (ADR 003): every factory must build a usable object, the
 * integration-pending ports fail fast and loud, and the one live wire (the
 * JDK HttpClient webhook sender) is wired with the configured timeouts.
 */
class GatewayConfigTest {

    private final GatewayConfig config = new GatewayConfig();

    // in-tree mirrors of the component-scanned JPA adapters
    private final InMemoryApiKeyRepository keys = new InMemoryApiKeyRepository();
    private final InMemoryWebhookSubscriptionRepository subscriptions =
            new InMemoryWebhookSubscriptionRepository();
    private final InMemoryWebhookDeliveryRepository deliveries =
            new InMemoryWebhookDeliveryRepository();
    private final InMemoryQuotaStore quotas = new InMemoryQuotaStore();
    private final InMemoryIdempotencyCache idempotency = new InMemoryIdempotencyCache();
    private final MutableClock clock =
            new MutableClock(Instant.parse("2026-09-01T10:00:00Z"));

    @Test
    void clockBeanReturnsUtcNow() {
        Clock bean = config.clock();
        Instant before = Instant.now().minusSeconds(5);
        Instant after = Instant.now().plusSeconds(5);
        assertTrue(bean.instant().isAfter(before) && bean.instant().isBefore(after));
        assertEquals(java.time.ZoneOffset.UTC, bean.getZone());
    }

    @Test
    void randomnessBeanIsTheSecureRandomAdapter() {
        assertInstanceOf(SecureRandomRandomness.class, config.randomness());
    }

    @Test
    void envelopeCodecBeanBuildsDeterministicPayloads() {
        com.sharkpay.gateway.events.EnvelopeCodec codec = config.envelopeCodec();
        assertNotNull(codec);
        tools.jackson.databind.node.ObjectNode data = codec.newDataObject();
        data.put("k", "v");
        com.sharkpay.gateway.events.CloudEventEnvelope envelope =
                com.sharkpay.gateway.events.CloudEventEnvelope.of(UUID.randomUUID(),
                        "payments.payment.created.v1", "sharkpay/payments", "pay_1",
                        clock.instant(), data);
        assertEquals(codec.outboundPayload(envelope, "payment.created"),
                codec.outboundPayload(envelope, "payment.created"));
    }

    @Test
    void webhookSenderBeanIsTheRealJdkClientWithConfiguredTimeouts() {
        WebhookSender sender = config.webhookSender(1500, 2500);
        assertInstanceOf(JdkHttpClientWebhookSender.class, sender);
        // the no-arg convenience constructor documents the defaults
        assertInstanceOf(JdkHttpClientWebhookSender.class, new JdkHttpClientWebhookSender());
    }

    @Test
    void upstreamPlaceholderFailsFastAndLoud() {
        UpstreamPort upstream = config.upstreamPort();
        assertInstanceOf(IntegrationPendingUpstream.class, upstream);
        UUID principal = UUID.randomUUID();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> upstream.forward(new UpstreamPort.UpstreamRequest("POST", "/v1/payments",
                        "{}", principal)));
        assertTrue(error.getMessage().contains("UpstreamPort adapter is not wired yet"));
        assertTrue(error.getMessage().contains("POST /v1/payments"));
        assertTrue(error.getMessage().contains(principal.toString()));
    }

    @Test
    void eventSourcePlaceholderFailsFastAndLoud() {
        EventSource source = config.eventSource();
        assertInstanceOf(IntegrationPendingEventSource.class, source);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> source.start(event -> 0));
        assertTrue(error.getMessage().contains("EventSource adapter is not wired yet"));
    }

    @Test
    void allUseCaseBeanMethodsBuildWorkingObjects() {
        SequentialRandomness randomness = new SequentialRandomness();
        com.sharkpay.gateway.events.EnvelopeCodec codec =
                new com.sharkpay.gateway.events.EnvelopeCodec(JsonMapper.builder().build());
        RecordingWebhookSender sender = new RecordingWebhookSender();

        CreateApiKeyUseCase createKey = config.createApiKeyUseCase(keys, randomness, clock);
        RotateApiKeyUseCase rotateKey = config.rotateApiKeyUseCase(keys, randomness, clock);
        ApiKeyAdminUseCase admin = config.apiKeyAdminUseCase(keys, clock);
        CreateWebhookSubscriptionUseCase createSubscription =
                config.createWebhookSubscriptionUseCase(subscriptions, randomness, clock);
        WebhookSubscriptionLifecycleUseCase lifecycle =
                config.webhookSubscriptionLifecycleUseCase(subscriptions, clock);
        WebhookDeliveryUseCase deliveryOps =
                config.webhookDeliveryUseCase(deliveries, lifecycle, clock);
        DispatchEventUseCase dispatcher = config.dispatchEventUseCase(subscriptions, deliveries,
                randomness, codec, clock);
        DeliveryAttemptUseCase worker = config.deliveryAttemptUseCase(deliveries, subscriptions,
                sender, clock);
        PassthroughService passthrough = config.passthroughService(
                new com.sharkpay.gateway.fakes.FakeUpstream(), idempotency);
        SandboxPaymentService sandbox = config.sandboxPaymentService(randomness, codec,
                dispatcher, clock);
        WebhookDeliverySweeper sweeper = config.webhookDeliverySweeper(worker, clock);

        assertNotNull(createKey);
        assertNotNull(rotateKey);
        assertNotNull(admin);
        assertNotNull(createSubscription);
        assertNotNull(lifecycle);
        assertNotNull(deliveryOps);
        assertNotNull(dispatcher);
        assertNotNull(worker);
        assertNotNull(passthrough);
        assertNotNull(sandbox);
        assertNotNull(sweeper);

        // and one end-to-end smoke on the built beans: create a key, use it
        CreateApiKeyUseCase.Result key = createKey.create(UUID.randomUUID(),
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.WEBHOOKS_MANAGE), null, null);
        assertTrue(key.plaintext().startsWith("sp_live_"));
        // a webhook subscription + event + sweep delivers
        createSubscription.create(UUID.randomUUID(), "https://merchant.example.com/h",
                java.util.List.of("payment.*"), "whsec_0123456789abcdef");
        assertEquals(1, dispatcher.onEvent(com.sharkpay.gateway.events.CloudEventEnvelope.of(
                UUID.randomUUID(), "payments.payment.succeeded.v1", "sharkpay/payments",
                "pay_1", clock.instant(), codec.newDataObject())));
        clock.advance(Duration.ofMinutes(1));
        DeliveryAttemptUseCase.Summary summary = worker.processDue(clock.instant());
        assertEquals(1, summary.delivered());
    }
}
