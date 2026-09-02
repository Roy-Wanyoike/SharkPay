package com.sharkpay.gateway.config;

import com.sharkpay.gateway.ports.ApiKeyRepository;
import com.sharkpay.gateway.ports.EventSource;
import com.sharkpay.gateway.ports.IdempotencyCache;
import com.sharkpay.gateway.ports.QuotaStore;
import com.sharkpay.gateway.ports.Randomness;
import com.sharkpay.gateway.ports.UpstreamPort;
import com.sharkpay.gateway.ports.WebhookSender;
import com.sharkpay.gateway.ports.WebhookDeliveryRepository;
import com.sharkpay.gateway.ports.WebhookSubscriptionRepository;
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
import com.sharkpay.gateway.events.EnvelopeCodec;
import com.sharkpay.gateway.webhook.JdkHttpClientWebhookSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * Production wiring of the hexagon, mirroring the wallet service's
 * {@code WalletConfig}: use-case beans depend only on ports.
 *
 * <ul>
 *   <li>storage-backed ports (api keys, subscriptions, deliveries, quotas,
 *       idempotency) — the JPA adapters in the storage package
 *       ({@code @Repository}, component-scanned, against the Flyway-managed
 *       schema);</li>
 *   <li>{@link WebhookSender} — the real JDK HttpClient adapter (the one
 *       live wire in this wave: outbound delivery to registered endpoints);</li>
 *   <li>{@link UpstreamPort} / {@link EventSource} — fail-fast
 *       integration-pending placeholders until the real HTTP routing and
 *       NATS/Kafka bindings land (ADR 003 §3);</li>
 *   <li>{@link Randomness} — {@link SecureRandomRandomness}.</li>
 * </ul>
 *
 * <p>Local tests never boot this context: they assemble the same use-cases
 * on the in-tree fakes ({@code com.sharkpay.gateway.fakes} in src/test).</p>
 */
@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Randomness randomness() {
        return new SecureRandomRandomness();
    }

    @Bean
    public EnvelopeCodec envelopeCodec() {
        // the codec's mapper is independent of the HTTP message converter:
        // deterministic, explicitly-built payload bytes are what the HMAC
        // signature covers, so they must never depend on web config
        return new EnvelopeCodec(JsonMapper.builder().build());
    }

    @Bean
    public WebhookSender webhookSender(
            @Value("${gateway.webhook.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${gateway.webhook.request-timeout-ms:10000}") long requestTimeoutMs) {
        return new JdkHttpClientWebhookSender(Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(requestTimeoutMs));
    }

    @Bean
    public UpstreamPort upstreamPort() {
        return new IntegrationPendingUpstream();
    }

    @Bean
    public EventSource eventSource() {
        return new IntegrationPendingEventSource();
    }

    @Bean
    public CreateApiKeyUseCase createApiKeyUseCase(ApiKeyRepository keys, Randomness randomness,
                                                    Clock clock) {
        return new CreateApiKeyUseCase(keys, randomness, clock);
    }

    @Bean
    public RotateApiKeyUseCase rotateApiKeyUseCase(ApiKeyRepository keys, Randomness randomness,
                                                    Clock clock) {
        return new RotateApiKeyUseCase(keys, randomness, clock);
    }

    @Bean
    public ApiKeyAdminUseCase apiKeyAdminUseCase(ApiKeyRepository keys, Clock clock) {
        return new ApiKeyAdminUseCase(keys, clock);
    }

    @Bean
    public CreateWebhookSubscriptionUseCase createWebhookSubscriptionUseCase(
            WebhookSubscriptionRepository subscriptions, Randomness randomness, Clock clock) {
        return new CreateWebhookSubscriptionUseCase(subscriptions, randomness, clock);
    }

    @Bean
    public WebhookSubscriptionLifecycleUseCase webhookSubscriptionLifecycleUseCase(
            WebhookSubscriptionRepository subscriptions, Clock clock) {
        return new WebhookSubscriptionLifecycleUseCase(subscriptions, clock);
    }

    @Bean
    public WebhookDeliveryUseCase webhookDeliveryUseCase(
            WebhookDeliveryRepository deliveries,
            WebhookSubscriptionLifecycleUseCase subscriptions, Clock clock) {
        return new WebhookDeliveryUseCase(deliveries, subscriptions, clock);
    }

    @Bean
    public DispatchEventUseCase dispatchEventUseCase(
            WebhookSubscriptionRepository subscriptions,
            WebhookDeliveryRepository deliveries, Randomness randomness,
            EnvelopeCodec codec, Clock clock) {
        return new DispatchEventUseCase(subscriptions, deliveries, randomness, codec, clock);
    }

    @Bean
    public DeliveryAttemptUseCase deliveryAttemptUseCase(
            WebhookDeliveryRepository deliveries,
            WebhookSubscriptionRepository subscriptions, WebhookSender sender, Clock clock) {
        return new DeliveryAttemptUseCase(deliveries, subscriptions, sender, clock);
    }

    @Bean
    public PassthroughService passthroughService(UpstreamPort upstream,
                                                 IdempotencyCache idempotency) {
        return new PassthroughService(upstream, idempotency);
    }

    @Bean
    public SandboxPaymentService sandboxPaymentService(Randomness randomness,
                                                        EnvelopeCodec codec,
                                                        DispatchEventUseCase dispatcher,
                                                        Clock clock) {
        return new SandboxPaymentService(randomness, codec, dispatcher, clock);
    }

    @Bean
    public WebhookDeliverySweeper webhookDeliverySweeper(DeliveryAttemptUseCase worker,
                                                         Clock clock) {
        return new WebhookDeliverySweeper(worker, clock);
    }
}
