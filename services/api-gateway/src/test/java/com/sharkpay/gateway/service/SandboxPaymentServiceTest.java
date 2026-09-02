package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.events.EnvelopeCodec;
import com.sharkpay.gateway.fakes.InMemoryWebhookDeliveryRepository;
import com.sharkpay.gateway.fakes.InMemoryWebhookSubscriptionRepository;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sandbox simulated provider: deterministic accept→settle script, full
 * validation of create inputs, one webhook event per step dispatched with
 * the payments payload shape, ephemeral in-memory state.
 */
class SandboxPaymentServiceTest {

    private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

    private final InMemoryWebhookSubscriptionRepository subscriptions =
            new InMemoryWebhookSubscriptionRepository();
    private final InMemoryWebhookDeliveryRepository deliveries =
            new InMemoryWebhookDeliveryRepository();
    private final MutableClock clock = new MutableClock(START);
    private final EnvelopeCodec codec = new EnvelopeCodec(JsonMapper.builder().build());
    private final DispatchEventUseCase dispatcher = new DispatchEventUseCase(subscriptions,
            deliveries, new SequentialRandomness(), codec, clock);
    private final SandboxPaymentService sandbox = new SandboxPaymentService(
            new SequentialRandomness(), codec, dispatcher, clock);

    private void subscribe() {
        new CreateWebhookSubscriptionUseCase(subscriptions, new SequentialRandomness(), clock)
                .create(java.util.UUID.randomUUID(), "https://merchant.example.com/hooks",
                        List.of("payment.*"), "whsec_0123456789abcdef");
    }

    @Test
    void createStartsTheScriptAtCreatedAndDispatchesPaymentCreated() {
        subscribe();
        SandboxPaymentService.SandboxPayment payment = sandbox.create(150000, "KES",
                "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "honeycoin");

        assertEquals("CREATED", payment.state());
        assertEquals(150000, payment.amountMinor());
        assertEquals("KES", payment.currency());
        assertEquals(2, payment.exponent());
        assertEquals("wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", payment.destinationWallet());
        assertEquals("honeycoin", payment.rail());
        assertEquals(START, payment.createdAt());
        assertTrue(payment.id().startsWith("pay_"));

        // payment.created dispatched with the full payments payload shape
        assertEquals(1, deliveries.all().size());
        WebhookDelivery delivery = deliveries.all().values().iterator().next();
        assertEquals("payment.created", delivery.eventType());
        // the delivery dedupe key is the envelope id (a UUID), not the payment id
        assertTrue(delivery.eventId().matches(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89ab][0-9a-fA-F]{3}"
                        + "-[0-9a-fA-F]{12}$"), delivery.eventId());
        String payload = delivery.payload();
        assertTrue(payload.contains("\"source\":\"sharkpay/sandbox\""), payload);
        assertTrue(payload.contains("\"payment_id\":\"" + payment.id() + "\""), payload);
        assertTrue(payload.contains("\"state\":\"CREATED\""), payload);
        assertTrue(payload.contains("\"amount_minor\":150000"), payload);
        assertTrue(payload.contains("\"currency\":\"KES\""), payload);
        assertTrue(payload.contains("\"exponent\":2"), payload);
        assertTrue(payload.contains("\"fee\":{\"amount_minor\":0"), payload);
        assertTrue(payload.contains("\"rail\":\"honeycoin\""), payload);
    }

    @Test
    void eachPollAdvancesExactlyOneStepAndSucceededIsStable() {
        subscribe();
        SandboxPaymentService.SandboxPayment payment = sandbox.create(100, "USD",
                "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "mpesa");

        SandboxPaymentService.SandboxPayment pending = sandbox.get(payment.id());
        assertEquals("PENDING_PROVIDER", pending.state());
        SandboxPaymentService.SandboxPayment succeeded = sandbox.get(payment.id());
        assertEquals("SUCCEEDED", succeeded.state());
        // further polls keep the terminal state
        assertEquals("SUCCEEDED", sandbox.get(payment.id()).state());
        assertEquals("SUCCEEDED", sandbox.get(payment.id()).state());
        // created + pending_provider + succeeded = 3 events total
        assertEquals(3, deliveries.all().size());
        // money is carried unchanged through the script
        assertEquals(100, succeeded.amountMinor());
        assertEquals("USD", succeeded.currency());
        assertEquals(2, succeeded.exponent());
    }

    @Test
    void stablecoinsUseExponentSix() {
        subscribe();
        SandboxPaymentService.SandboxPayment payment = sandbox.create(2_500_000, "USDC",
                "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "on_chain");
        assertEquals(6, payment.exponent());
        String payload = deliveries.all().values().iterator().next().payload();
        assertTrue(payload.contains("\"exponent\":6"), payload);
        assertTrue(payload.contains("\"currency\":\"USDC\""), payload);
    }

    @Test
    void currencyIsCaseInsensitiveButValidated() {
        assertEquals("KES", sandbox.create(1, "kes", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "bank")
                .currency());
        assertEquals("USD", sandbox.create(1, "Usd", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "bank")
                .currency());
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> sandbox.create(1, "JPY", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "bank"));
        assertTrue(unknown.getMessage().contains("unsupported sandbox currency"));
    }

    @Test
    void createValidatesAmountWalletAndRail() {
        assertThrows(IllegalArgumentException.class,
                () -> sandbox.create(0, "KES", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "bank"));
        assertThrows(IllegalArgumentException.class,
                () -> sandbox.create(-5, "KES", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "bank"));
        IllegalArgumentException badWallet = assertThrows(IllegalArgumentException.class,
                () -> sandbox.create(1, "KES", "not-a-wallet", "bank"));
        assertTrue(badWallet.getMessage().contains("wal_"));
        IllegalArgumentException badRail = assertThrows(IllegalArgumentException.class,
                () -> sandbox.create(1, "KES", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "pony"));
        assertTrue(badRail.getMessage().contains("rail"));
        assertThrows(NullPointerException.class,
                () -> sandbox.create(1, null, "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "bank"));
        assertThrows(NullPointerException.class,
                () -> sandbox.create(1, "KES", null, "bank"));
        assertThrows(NullPointerException.class,
                () -> sandbox.create(1, "KES", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", null));
    }

    @Test
    void unknownSandboxPaymentsAreMissing() {
        NoSuchElementException missing = assertThrows(NoSuchElementException.class,
                () -> sandbox.get("pay_doesnotexist0000000000000"));
        assertTrue(missing.getMessage().contains("sandbox payment"));
    }

    @Test
    void theScriptIsPerPaymentAndClockAdvancesDoNotMutateState() {
        SandboxPaymentService.SandboxPayment first = sandbox.create(1, "KES",
                "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "bank");
        clock.advance(Duration.ofHours(3));
        SandboxPaymentService.SandboxPayment second = sandbox.create(2, "KES",
                "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "bank");

        // each payment advances only on its own polls
        assertEquals("PENDING_PROVIDER", sandbox.get(first.id()).state());
        assertEquals("SUCCEEDED", sandbox.get(first.id()).state());
        // second is untouched by first's polls
        assertEquals("PENDING_PROVIDER", sandbox.get(second.id()).state());
        assertEquals("SUCCEEDED", sandbox.get(second.id()).state());
    }
}
