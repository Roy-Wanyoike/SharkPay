package com.sharkpay.gateway.events;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The webhook event catalog: the 17 public names of webhooks.yaml (EventName)
 * mapped 1:1 onto the internal versioned topics of events.md, plus the
 * catalog closure (internal-only topics with no public name).
 */
class EventTypeCatalogTest {

    @Test
    void everyPublicCatalogEntryResolvesToItsTopicAndBack() {
        for (EventTypeCatalog entry : EventTypeCatalog.values()) {
            Optional<EventTypeCatalog> byTopic = EventTypeCatalog.fromTopic(entry.topic());
            assertTrue(byTopic.isPresent(), entry.topic() + " must resolve");
            assertEquals(entry, byTopic.orElseThrow());
            assertEquals(entry, EventTypeCatalog.fromPublicName(entry.publicName()).orElseThrow());
        }
    }

    @Test
    void theCatalogMatchesTheWebhooksYamlEventNameEnum() {
        Set<String> publicNames = new HashSet<>();
        for (EventTypeCatalog entry : EventTypeCatalog.values()) {
            publicNames.add(entry.publicName());
        }
        // contracts/openapi/v1/webhooks.yaml EventName enum (17 entries)
        Set<String> contract = Set.of(
                "payment.created", "payment.pending_provider", "payment.succeeded",
                "payment.failed", "payment.expired", "payment.reversed",
                "payout.created", "payout.processing", "payout.sent", "payout.succeeded",
                "payout.failed", "payout.returned",
                "transfer.succeeded",
                "fx.quote.locked", "fx.conversion.executed",
                "wallet.balance.changed",
                "risk.case.opened");
        assertEquals(contract, publicNames);
        assertEquals(17, publicNames.size());
    }

    @Test
    void topicsCarryTheV1SuffixAndPublicNamesDoNot() {
        for (EventTypeCatalog entry : EventTypeCatalog.values()) {
            assertTrue(entry.topic().endsWith(".v1"), entry.topic());
            assertFalse(entry.publicName().contains(".v1"), entry.publicName());
            assertNotEquals(entry.topic(), entry.publicName());
        }
    }

    @Test
    void unknownTopicsAndNamesResolveEmpty() {
        assertTrue(EventTypeCatalog.fromTopic("some.unknown.topic.v1").isEmpty());
        assertTrue(EventTypeCatalog.fromTopic("").isEmpty());
        assertTrue(EventTypeCatalog.fromTopic(null).isEmpty());
        assertTrue(EventTypeCatalog.fromPublicName("payment.escaped").isEmpty());
        // the versioned topic name is NOT a public name and vice versa
        assertTrue(EventTypeCatalog.fromPublicName("payments.payment.succeeded.v1").isEmpty());
        assertTrue(EventTypeCatalog.fromTopic("payment.succeeded").isEmpty());
    }

    @Test
    void internalOnlyTopicsAreKnownButHaveNoPublicName() {
        assertEquals(Set.of("risk.decision.v1", "risk.case.resolved.v1",
                "ledger.posting.committed.v1"), EventTypeCatalog.INTERNAL_ONLY_TOPICS);
        for (String topic : EventTypeCatalog.INTERNAL_ONLY_TOPICS) {
            assertTrue(EventTypeCatalog.isKnownTopic(topic), topic);
            assertTrue(EventTypeCatalog.fromTopic(topic).isEmpty(),
                    topic + " must have no public webhook name");
        }
        // catalog topics are known topics too
        for (EventTypeCatalog entry : EventTypeCatalog.values()) {
            assertTrue(EventTypeCatalog.isKnownTopic(entry.topic()));
        }
        assertFalse(EventTypeCatalog.isKnownTopic("some.unknown.topic.v1"));
        assertFalse(EventTypeCatalog.isKnownTopic(null));
    }
}
