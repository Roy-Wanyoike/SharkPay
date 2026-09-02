package com.sharkpay.gateway.events;

import java.util.Optional;

/**
 * The webhook event catalog: the 17 unversioned public event names from
 * contracts/openapi/v1/webhooks.yaml (EventName) mapped to the internal
 * versioned Kafka topic names from contracts/events/events.md.
 *
 * <p>Webhook payloads reuse the same envelope with the unversioned catalog
 * type, so public consumers are insulated from internal topic versioning
 * (events.md "Webhook mapping"). Topics without a catalog entry
 * ({@code risk.decision.v1}, {@code risk.case.resolved.v1},
 * {@code ledger.posting.committed.v1}) have no public name — the
 * dispatcher ignores them (catalog closure).</p>
 */
public enum EventTypeCatalog {

    PAYMENT_CREATED("payment.created", "payments.payment.created.v1"),
    PAYMENT_PENDING_PROVIDER("payment.pending_provider", "payments.payment.pending_provider.v1"),
    PAYMENT_SUCCEEDED("payment.succeeded", "payments.payment.succeeded.v1"),
    PAYMENT_FAILED("payment.failed", "payments.payment.failed.v1"),
    PAYMENT_EXPIRED("payment.expired", "payments.payment.expired.v1"),
    PAYMENT_REVERSED("payment.reversed", "payments.payment.reversed.v1"),

    PAYOUT_CREATED("payout.created", "payouts.payout.created.v1"),
    PAYOUT_PROCESSING("payout.processing", "payouts.payout.processing.v1"),
    PAYOUT_SENT("payout.sent", "payouts.payout.sent.v1"),
    PAYOUT_SUCCEEDED("payout.succeeded", "payouts.payout.succeeded.v1"),
    PAYOUT_FAILED("payout.failed", "payouts.payout.failed.v1"),
    PAYOUT_RETURNED("payout.returned", "payouts.payout.returned.v1"),

    TRANSFER_SUCCEEDED("transfer.succeeded", "transfers.transfer.succeeded.v1"),

    FX_QUOTE_LOCKED("fx.quote.locked", "fx.quote.locked.v1"),
    FX_CONVERSION_EXECUTED("fx.conversion.executed", "fx.conversion.executed.v1"),

    WALLET_BALANCE_CHANGED("wallet.balance.changed", "wallet.balance.changed.v1"),

    RISK_CASE_OPENED("risk.case.opened", "risk.case.opened.v1");

    private final String publicName;
    private final String topic;

    EventTypeCatalog(String publicName, String topic) {
        this.publicName = publicName;
        this.topic = topic;
    }

    /** The unversioned webhook catalog name (the outbound envelope type). */
    public String publicName() {
        return publicName;
    }

    /** The internal versioned Kafka topic (the inbound envelope type). */
    public String topic() {
        return topic;
    }

    /** Resolves an inbound topic name to its catalog entry. */
    public static Optional<EventTypeCatalog> fromTopic(String topic) {
        for (EventTypeCatalog entry : values()) {
            if (entry.topic.equals(topic)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Resolves a public catalog name (also validates webhook registration patterns). */
    public static Optional<EventTypeCatalog> fromPublicName(String name) {
        for (EventTypeCatalog entry : values()) {
            if (entry.publicName.equals(name)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Internal topics that exist in the event registry but carry no public
     * webhook name (events.md): risk decisions, case resolutions and the
     * authoritative ledger feed are internal-only — the dispatcher ignores
     * them, the intake still accepts them.
     */
    public static final java.util.Set<String> INTERNAL_ONLY_TOPICS = java.util.Set.of(
            "risk.decision.v1",
            "risk.case.resolved.v1",
            "ledger.posting.committed.v1");

    /** Whether the topic is in the event registry (catalog or internal-only). */
    public static boolean isKnownTopic(String topic) {
        return topic != null && (fromTopic(topic).isPresent()
                || INTERNAL_ONLY_TOPICS.contains(topic));
    }
}
