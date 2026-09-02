package com.sharkpay.payouts.events;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 (ADR 003) contract evidence: every payouts.payout.*.v1 event the
 * service publishes is serialized (Jackson 3, snake_case, NON_NULL) and
 * structurally validated against contracts/events/payouts.payout.v1.json —
 * read-only, binding. UUID v7 ids, redacted destination details and the
 * one-event-per-transition rule are pinned.
 */
class PayoutEventsTest {

    private final PayoutsTestEnv env = new PayoutsTestEnv();

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(value ->
                    value.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void everyPublishedEventValidatesAgainstThePayoutContractSchema() {
        driveFullLifecycle();
        // cancel and risk-deny have no catalog event type (documented deviation)
        env.cancelPayout.cancel("cancel-key", env.createPayout("k-cancel").id(), null);

        List<CloudEvent> published = env.events.events();
        assertThat(published).isNotEmpty();
        JsonSchemaCheck check = JsonSchemaCheck.load("payouts.payout.v1.json");
        for (CloudEvent event : published) {
            JsonNode tree = mapper.valueToTree(event);
            assertThat(check.errors(tree))
                    .as("event %s must validate against payouts.payout.v1.json:\n%s",
                            event.type(), tree)
                    .isEmpty();
        }
    }

    @Test
    void everyEventCarriesAUniqueUuidV7IdAndTheCatalogSource() {
        driveFullLifecycle();

        List<String> ids = env.events.events().stream().map(CloudEvent::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        for (String id : ids) {
            // UUID v7: version nibble 7, RFC 9562 variant — consumers dedupe on it
            assertThat(id).matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
        for (CloudEvent event : env.events.events()) {
            assertThat(event.source()).isEqualTo(CloudEvent.PAYOUTS_SOURCE);
            assertThat(event.specversion()).isEqualTo("1.0");
        }
    }

    @Test
    void onlyCatalogEventTypesArePublished() {
        driveFullLifecycle();
        // cancel + risk-deny have no catalog event type (documented deviation)
        Payout cancellable = env.createPayout("k-cancel");
        env.cancelPayout.cancel("cancel-key-2", cancellable.id(), null);
        Payout blockable = env.createPayout("k-block");
        env.riskDecisions.apply(blockable.id(), "DENY", "sanctions");

        List<String> types = env.events.events().stream().map(CloudEvent::type).distinct().toList();
        assertThat(types).containsExactlyInAnyOrder(
                PayoutEvents.CREATED, PayoutEvents.PROCESSING, PayoutEvents.SENT,
                PayoutEvents.SUCCEEDED, PayoutEvents.FAILED, PayoutEvents.RETURNED);
    }

    @Test
    void theLifecycleEmitsOneEventPerCataloguedTransition() {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue(); // PROCESSING
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.PENDING,
                null, null, null, null, null); // SENT
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null); // SUCCEEDED

        // idempotent replays never re-emit
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.PENDING,
                null, null, null, null, null);
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);

        assertThat(env.events.events()).hasSize(4);
        assertThat(env.events.eventsOfType(PayoutEvents.CREATED)).hasSize(1);
        assertThat(env.events.eventsOfType(PayoutEvents.PROCESSING)).hasSize(1);
        assertThat(env.events.eventsOfType(PayoutEvents.SENT)).hasSize(1);
        assertThat(env.events.eventsOfType(PayoutEvents.SUCCEEDED)).hasSize(1);
    }

    @Test
    void thePayloadCarriesMoneyAndRedactsDestinationDetails() {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);

        CloudEvent created = env.events.eventsOfType(PayoutEvents.CREATED).get(0);
        PayoutEvents.PayoutEventData createdData = (PayoutEvents.PayoutEventData) created.data();
        assertThat(created.subject()).isEqualTo(payout.id());
        assertThat(created.occurredAt()).isEqualTo(PayoutsTestEnv.START);
        assertThat(createdData.payout_id()).isEqualTo(payout.id());
        assertThat(createdData.state()).isEqualTo(PayoutState.PENDING_RISK.name());
        assertThat(createdData.amount().amount_minor()).isEqualTo(500_000);
        assertThat(createdData.amount().currency()).isEqualTo("KES");
        assertThat(createdData.amount().exponent()).isEqualTo(2);
        assertThat(createdData.fee().amount_minor()).isEqualTo(10_500);
        // G5: only the rail type travels — never the msisdn
        assertThat(createdData.destination_type()).isEqualTo("mpesa");
        assertThat(createdData.reason()).isNull();
        assertThat(createdData.entry_id()).isNull();

        CloudEvent succeeded = env.events.eventsOfType(PayoutEvents.SUCCEEDED).get(0);
        PayoutEvents.PayoutEventData settled = (PayoutEvents.PayoutEventData) succeeded.data();
        assertThat(settled.state()).isEqualTo(PayoutState.SUCCEEDED.name());
        assertThat(settled.entry_id()).isEqualTo(payout.settleEntryId().toString());
        assertThat(settled.provider_ref()).isEqualTo(payout.providerRef());
    }

    @Test
    void theFailedEventCarriesTheReasonAndTheHoldEntry() {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.FAILED, null,
                "rail failure", null, null, null);

        CloudEvent failed = env.events.eventsOfType(PayoutEvents.FAILED).get(0);
        PayoutEvents.PayoutEventData data = (PayoutEvents.PayoutEventData) failed.data();
        assertThat(data.state()).isEqualTo(PayoutState.FAILED.name());
        assertThat(data.reason()).isEqualTo("rail failure");
        assertThat(data.entry_id()).isEqualTo(payout.holdEntryId().toString());
    }

    @Test
    void theReturnedEventCarriesTheReasonAndTheCompensationEntry() {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, "msisdn_not_registered", 500_000L, "KES", "ret-1");

        CloudEvent returned = env.events.eventsOfType(PayoutEvents.RETURNED).get(0);
        PayoutEvents.PayoutEventData data = (PayoutEvents.PayoutEventData) returned.data();
        assertThat(data.state()).isEqualTo(PayoutState.RETURNED.name());
        assertThat(data.reason()).isEqualTo("msisdn_not_registered");
        assertThat(data.entry_id()).isEqualTo(payout.returnEntryId().toString());
        // the serialized payload passes the schema's closed object
        JsonSchemaCheck check = JsonSchemaCheck.load("payouts.payout.v1.json");
        assertThat(check.errors(mapper.valueToTree(returned))).isEmpty();
    }

    @Test
    void theEnvelopeGuardsItsRequiredFields() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> new CloudEvent(null, PayoutEvents.CREATED, "1.0",
                                        "sharkpay/payouts", "pot_x", PayoutsTestEnv.START, Map.of())))
                        .hasMessageContaining("event id is required"),
                () -> assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> new CloudEvent("id", null, "1.0", "sharkpay/payouts",
                                        "pot_x", PayoutsTestEnv.START, Map.of())))
                        .hasMessageContaining("event type is required"),
                () -> assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> new CloudEvent("id", PayoutEvents.CREATED, "1.0", " ",
                                        "pot_x", PayoutsTestEnv.START, Map.of())))
                        .hasMessageContaining("event source is required"),
                () -> assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> new CloudEvent("id", PayoutEvents.CREATED, "1.0",
                                        "sharkpay/payouts", null, PayoutsTestEnv.START, Map.of())))
                        .hasMessageContaining("event subject is required"),
                () -> assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> new CloudEvent("id", PayoutEvents.CREATED, "1.0",
                                        "sharkpay/payouts", "pot_x", null, Map.of())))
                        .hasMessageContaining("event occurredAt is required"),
                () -> assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> new CloudEvent("id", PayoutEvents.CREATED, "1.0",
                                        "sharkpay/payouts", "pot_x", PayoutsTestEnv.START, null)))
                        .hasMessageContaining("event data is required"));
        assertThat(CloudEvent.SPECVERSION).isEqualTo("1.0");
        assertThat(CloudEvent.PAYOUTS_SOURCE).isEqualTo("sharkpay/payouts");
        assertThat(CloudEvent.TRANSFERS_SOURCE).isEqualTo("sharkpay/transfers");
    }

    @Test
    void uuidV7IdsAreTimeOrderedAndUnique() {
        var ids = new java.util.HashSet<String>();
        long firstTimestamp = 0;
        for (int i = 0; i < 200; i++) {
            java.util.UUID id = EventIds.uuidV7();
            assertThat(id.version()).isEqualTo(7);
            ids.add(id.toString());
            long timestamp = id.getMostSignificantBits() >>> 16;
            assertThat(timestamp).isGreaterThanOrEqualTo(firstTimestamp);
            firstTimestamp = Math.max(firstTimestamp, timestamp);
            // ids minted now carry a current epoch-millisecond prefix
            assertThat(timestamp).isGreaterThan(1_700_000_000_000L);
        }
        assertThat(ids).hasSize(200);
    }

    /** Drives a payout through every catalogued transition. */
    private void driveFullLifecycle() {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue(); // PROCESSING
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.PENDING,
                null, null, null, null, null); // SENT
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null); // SUCCEEDED
        Payout payout2 = env.createPayout("k2");
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        env.providerResults.ingest(payout2.id(), ProviderGatewayPort.ProviderStatus.FAILED, null,
                "rail failure", null, null, null); // FAILED
        Payout payout3 = env.createPayout("k3");
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        env.providerResults.ingest(payout3.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);
        env.providerResults.ingest(payout3.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, "returned", 500_000L, "KES", "ret-1"); // RETURNED
    }
}
