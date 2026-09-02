package com.sharkpay.gateway.testsupport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sharkpay.gateway.api.ApiKeyAuthFilter;
import com.sharkpay.gateway.api.ApiKeyController;
import com.sharkpay.gateway.api.GlobalExceptionHandler;
import com.sharkpay.gateway.api.InternalEventController;
import com.sharkpay.gateway.api.PassthroughController;
import com.sharkpay.gateway.api.SandboxController;
import com.sharkpay.gateway.api.WebhookEndpointController;
import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.events.EnvelopeCodec;
import com.sharkpay.gateway.fakes.FakeUpstream;
import com.sharkpay.gateway.fakes.InMemoryApiKeyRepository;
import com.sharkpay.gateway.fakes.InMemoryIdempotencyCache;
import com.sharkpay.gateway.fakes.InMemoryQuotaStore;
import com.sharkpay.gateway.fakes.InMemoryWebhookDeliveryRepository;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.RecordingWebhookSender;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.fakes.FakeEventFeed;
import com.sharkpay.gateway.ports.Randomness;
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
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles the full gateway object graph on in-memory fakes with a mutable
 * clock, shared by service and standalone-MockMvc controller tests (no
 * Spring context, per ADR 003 — no @SpringBootTest, no database). The
 * {@link ApiKeyAuthFilter} is attached to the standalone MockMvc so the
 * auth/scope/quota front door behaves exactly like production.
 */
public final class GatewayTestEnv {

    public static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

    public final MutableClock clock;
    public final SequentialRandomness randomness;
    public final InMemoryApiKeyRepository keys;
    public final InMemoryWebhookSubscriptionRepository subscriptions;
    public final InMemoryWebhookDeliveryRepository deliveries;
    public final InMemoryQuotaStore quotas;
    public final InMemoryIdempotencyCache idempotency;
    public final RecordingWebhookSender sender;
    public final FakeUpstream upstream;
    public final EnvelopeCodec codec;

    public final CreateApiKeyUseCase createKey;
    public final RotateApiKeyUseCase rotateKey;
    public final ApiKeyAdminUseCase keyAdmin;
    public final CreateWebhookSubscriptionUseCase createSubscription;
    public final WebhookSubscriptionLifecycleUseCase subscriptionLifecycle;
    public final WebhookDeliveryUseCase deliveryOps;
    public final DispatchEventUseCase dispatcher;
    public final DeliveryAttemptUseCase worker;
    public final PassthroughService passthrough;
    public final SandboxPaymentService sandbox;
    public final FakeEventFeed feed;

    public final ApiKeyAuthFilter authFilter;
    public final ApiKeyController apiKeyController;
    public final WebhookEndpointController webhookEndpointController;
    public final PassthroughController passthroughController;
    public final SandboxController sandboxController;
    public final InternalEventController internalEventController;
    public final GlobalExceptionHandler errorHandler;

    public GatewayTestEnv() {
        this(START);
    }

    public GatewayTestEnv(Instant start) {
        clock = new MutableClock(start);
        randomness = new SequentialRandomness();
        keys = new InMemoryApiKeyRepository();
        subscriptions = new InMemoryWebhookSubscriptionRepository();
        deliveries = new InMemoryWebhookDeliveryRepository();
        quotas = new InMemoryQuotaStore();
        idempotency = new InMemoryIdempotencyCache();
        sender = new RecordingWebhookSender();
        upstream = new FakeUpstream();
        codec = new EnvelopeCodec(JsonMapper.builder().build());

        createKey = new CreateApiKeyUseCase(keys, randomness, clock);
        rotateKey = new RotateApiKeyUseCase(keys, randomness, clock);
        keyAdmin = new ApiKeyAdminUseCase(keys, clock);
        createSubscription = new CreateWebhookSubscriptionUseCase(subscriptions, randomness,
                clock);
        subscriptionLifecycle = new WebhookSubscriptionLifecycleUseCase(subscriptions, clock);
        dispatcher = new DispatchEventUseCase(subscriptions, deliveries, randomness, codec, clock);
        deliveryOps = new WebhookDeliveryUseCase(deliveries, subscriptionLifecycle, clock);
        worker = new DeliveryAttemptUseCase(deliveries, subscriptions, sender, clock);
        passthrough = new PassthroughService(upstream, idempotency);
        sandbox = new SandboxPaymentService(randomness, codec, dispatcher, clock);
        feed = new FakeEventFeed(dispatcher);

        authFilter = new ApiKeyAuthFilter(keys, quotas, clock);
        apiKeyController = new ApiKeyController(createKey, rotateKey, keyAdmin, idempotency, keys);
        webhookEndpointController = new WebhookEndpointController(createSubscription,
                subscriptionLifecycle, deliveryOps, idempotency, subscriptions);
        passthroughController = new PassthroughController(passthrough);
        sandboxController = new SandboxController(sandbox);
        internalEventController = new InternalEventController(dispatcher, codec);
        errorHandler = new GlobalExceptionHandler();
    }

    /** Registers a fresh principal id (the caller every resource is scoped to). */
    public UUID newPrincipal() {
        return UUID.randomUUID();
    }

    /**
     * Seeds an ACTIVE key for the principal with the given scopes and
     * returns it with its plaintext secret (for Authorization headers).
     */
    public SeededKey seedKey(UUID principal, Set<Scope> scopes, int rpm, long monthly) {
        CreateApiKeyUseCase.Result result = createKey.create(principal, scopes, rpm, monthly);
        return new SeededKey(result.key(), result.plaintext());
    }

    /** Convenience: full-power key (all scopes), default quotas. */
    public SeededKey seedFullKey(UUID principal) {
        return seedKey(principal, Set.of(Scope.values()), CreateApiKeyUseCase.DEFAULT_RPM_LIMIT,
                CreateApiKeyUseCase.DEFAULT_MONTHLY_LIMIT);
    }

    /** One delivery-worker sweep at the current clock. */
    public DeliveryAttemptUseCase.Summary sweep() {
        return worker.processDue(clock.instant());
    }

    /**
     * Standalone MockMvc with the Jackson 3 (tools.jackson) JSON converter
     * (ISO-8601 instants, NON_NULL inclusion) and the API key auth filter —
     * requests must carry {@code Authorization: Bearer <sk_...>}.
     */
    public MockMvc mockMvc() {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(value ->
                        value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // String converter first (mirrors Boot's default order) so the
        // passthrough controller's raw String bodies are relayed verbatim;
        // Jackson handles the DTO bodies of the management surface
        return MockMvcBuilders.standaloneSetup(apiKeyController, webhookEndpointController,
                        passthroughController, sandboxController, internalEventController)
                .setControllerAdvice(errorHandler)
                .setMessageConverters(
                        new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter(mapper))
                .setValidator(validator)
                .addFilters(authFilter)
                .build();
    }

    /** A seeded key plus its plaintext secret. */
    public record SeededKey(ApiKey key, String secret) {

        /** The Authorization header value for this key. */
        public String authorization() {
            return "Bearer " + secret;
        }
    }
}
