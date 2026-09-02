package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.events.CloudEventEnvelope;
import com.sharkpay.gateway.events.EventTypeCatalog;
import com.sharkpay.gateway.ports.EventConsumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * In-tree fake event feed: builds well-formed CloudEvent envelopes for the
 * catalog topics (payments shape per contracts/events/payments.payment.v1.json)
 * and delivers them to the {@link EventConsumer} — the executable spec of
 * what the real NATS/Kafka binding will push (and of the
 * {@code POST /internal/events} dev intake).
 */
public final class FakeEventFeed {

    private final EventConsumer consumer;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public FakeEventFeed(EventConsumer consumer) {
        this.consumer = consumer;
    }

    /** Delivers a payments.payment.succeeded.v1 envelope (full payload). */
    public int paymentSucceeded(String paymentId, long amountMinor, String currency) {
        ObjectNode data = mapper.createObjectNode();
        data.put("payment_id", paymentId);
        data.put("state", "SUCCEEDED");
        data.set("amount", money(amountMinor, currency));
        data.set("fee", money(0, currency));
        data.put("destination_wallet", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A");
        data.put("rail", "honeycoin");
        data.put("entry_id", "0192a7c5-1a2b-7c3d-9e4f-8a5b6c7d8e9f");
        return deliver(EventTypeCatalog.PAYMENT_SUCCEEDED, paymentId, data);
    }

    /** Delivers a payments.payment.created.v1 envelope. */
    public int paymentCreated(String paymentId) {
        return deliver(EventTypeCatalog.PAYMENT_CREATED, paymentId, minimalPaymentData(paymentId));
    }

    /** Delivers a payouts.payout.succeeded.v1 envelope (payouts shape). */
    public int payoutSucceeded(String payoutId) {
        ObjectNode data = mapper.createObjectNode();
        data.put("payout_id", payoutId);
        data.put("state", "SUCCEEDED");
        data.set("amount", money(1000, "KES"));
        data.set("fee", money(0, "KES"));
        data.put("source_wallet", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A");
        data.put("destination_type", "mpesa");
        data.put("entry_id", "0192a7c5-1a2b-7c3d-9e4f-8a5b6c7d8e9f");
        return deliver(EventTypeCatalog.PAYOUT_SUCCEEDED, payoutId, data);
    }

    /** Delivers an envelope for any topic with a minimal data object. */
    public int deliver(EventTypeCatalog event, String subject, JsonNode data) {
        return consumer.onEvent(new CloudEventEnvelope(UUID.randomUUID().toString(),
                event.topic(), CloudEventEnvelope.SPECVERSION, sourceOf(event.topic()), subject,
                Instant.now(), data));
    }

    /** Delivers a raw envelope with an arbitrary topic (unknown-topic tests). */
    public int deliverRaw(String topic, String eventId) {
        ObjectNode data = mapper.createObjectNode();
        data.put("ignored", true);
        return consumer.onEvent(new CloudEventEnvelope(eventId, topic,
                CloudEventEnvelope.SPECVERSION, "sharkpay/ledger",
                "ledger-entry", Instant.now(), data));
    }

    /** The CloudEvents source of a topic, per contracts/events/events.md producers. */
    private static String sourceOf(String topic) {
        if (topic.startsWith("payments.")) {
            return "sharkpay/payments";
        }
        if (topic.startsWith("transfers.")) {
            return "sharkpay/payouts";
        }
        if (topic.startsWith("payouts.")) {
            return "sharkpay/payouts";
        }
        if (topic.startsWith("fx.")) {
            return "sharkpay/fx";
        }
        if (topic.startsWith("wallet.")) {
            return "sharkpay/wallet";
        }
        if (topic.startsWith("risk.")) {
            return "sharkpay/risk";
        }
        return "sharkpay/unknown";
    }

    private ObjectNode minimalPaymentData(String paymentId) {
        ObjectNode data = mapper.createObjectNode();
        data.put("payment_id", paymentId);
        data.put("state", "CREATED");
        data.set("amount", money(150000, "KES"));
        data.set("fee", money(750, "KES"));
        data.put("destination_wallet", "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A");
        data.put("rail", "honeycoin");
        return data;
    }

    private ObjectNode money(long amountMinor, String currency) {
        ObjectNode money = mapper.createObjectNode();
        money.put("amount_minor", amountMinor);
        money.put("currency", currency);
        money.put("exponent", currency.equals("KES") ? 2 : 6);
        return money;
    }

    /** The mapper, for tests that need to build payloads. */
    public JsonMapper mapper() {
        return mapper;
    }
}
