package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.HttpsUrlRequiredException;
import com.sharkpay.gateway.domain.InvalidEventTypesException;
import com.sharkpay.gateway.domain.SubscriptionState;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook endpoint registration: https-only URL enforcement
 * (webhooks.yaml 422 http_url_required), catalog event names or globs
 * (422 invalid_events), caller-supplied HMAC secret, per-principal
 * scoping.
 */
class CreateWebhookSubscriptionUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();

    private final InMemoryWebhookSubscriptionRepository subscriptions =
            new InMemoryWebhookSubscriptionRepository();
    private final CreateWebhookSubscriptionUseCase useCase =
            new CreateWebhookSubscriptionUseCase(subscriptions, new SequentialRandomness(),
                    new MutableClock(NOW));

    @Test
    void registersAnActiveEndpointWithCatalogEventTypes() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = useCase.create(PRINCIPAL,
                "https://merchant.example.com/sharkpay/webhooks",
                List.of("payment.created", "payment.succeeded", "payment.failed"),
                "whsec_0123456789abcdef");

        assertEquals("wh_", subscription.id().substring(0, 3));
        assertEquals(PRINCIPAL, subscription.principalId());
        assertEquals(SubscriptionState.ACTIVE, subscription.state());
        assertEquals(3, subscription.eventPatterns().size());
        assertEquals(NOW, subscription.createdAt());
        assertEquals(0, subscription.consecutiveDeadDeliveries());
        assertTrue(subscription.matchesEvent("payment.created"));
        assertEquals(1, subscriptions.all().size());
    }

    @Test
    void globPatternsAreAcceptedAndMatch() {
        com.sharkpay.gateway.domain.WebhookSubscription subscription = useCase.create(PRINCIPAL,
                "https://merchant.example.com/hooks", List.of("payment.*", "payout.succeeded",
                        "*"),
                "whsec_0123456789abcdef");
        assertTrue(subscription.matchesEvent("payment.expired"));
        assertTrue(subscription.matchesEvent("payout.succeeded"));
        assertTrue(subscription.matchesEvent("wallet.balance.changed"));
    }

    @Test
    void httpUrlsAreRejectedHttpsOnlyEnforced() {
        HttpsUrlRequiredException plain = assertThrows(HttpsUrlRequiredException.class,
                () -> useCase.create(PRINCIPAL, "http://merchant.example.com/hooks",
                        List.of("payment.created"), "whsec_0123456789abcdef"));
        assertEquals("http://merchant.example.com/hooks", plain.url());
        assertThrows(HttpsUrlRequiredException.class,
                () -> useCase.create(PRINCIPAL, "ftp://merchant.example.com/hooks",
                        List.of("payment.created"), "whsec_0123456789abcdef"));
        assertThrows(HttpsUrlRequiredException.class,
                () -> useCase.create(PRINCIPAL, "HTTP://merchant.example.com/hooks",
                        List.of("payment.created"), "whsec_0123456789abcdef"));
        assertEquals(0, subscriptions.all().size());
    }

    @Test
    void unknownExactEventNamesAreRejectedFailClosed() {
        InvalidEventTypesException unknown = assertThrows(InvalidEventTypesException.class,
                () -> useCase.create(PRINCIPAL, "https://merchant.example.com/hooks",
                        List.of("payment.succeeded", "nonsense.event"), "whsec_0123456789abcdef"));
        assertTrue(unknown.getMessage().contains("nonsense.event"));
        // internal versioned topic names are not public catalog names either
        assertThrows(InvalidEventTypesException.class,
                () -> useCase.create(PRINCIPAL, "https://merchant.example.com/hooks",
                        List.of("payments.payment.succeeded.v1"), "whsec_0123456789abcdef"));
        assertThrows(InvalidEventTypesException.class,
                () -> useCase.create(PRINCIPAL, "https://merchant.example.com/hooks",
                        List.of(""), "whsec_0123456789abcdef"));
        assertThrows(InvalidEventTypesException.class,
                () -> useCase.create(PRINCIPAL, "https://merchant.example.com/hooks", null,
                        "whsec_0123456789abcdef"));
    }

    @Test
    void secretLengthBoundsMirrorTheContract() {
        assertThrows(IllegalArgumentException.class,
                () -> useCase.create(PRINCIPAL, "https://merchant.example.com/hooks",
                        List.of("payment.created"), "whsec_short"));
        assertThrows(IllegalArgumentException.class,
                () -> useCase.create(PRINCIPAL, "https://merchant.example.com/hooks",
                        List.of("payment.created"), "x".repeat(257)));
        assertEquals(0, subscriptions.all().size());
    }

    @Test
    void principalAndUrlAreRequired() {
        assertThrows(NullPointerException.class,
                () -> useCase.create(null, "https://merchant.example.com/hooks",
                        List.of("payment.created"), "whsec_0123456789abcdef"));
        assertThrows(NullPointerException.class,
                () -> useCase.create(PRINCIPAL, null, List.of("payment.created"),
                        "whsec_0123456789abcdef"));
    }
}
