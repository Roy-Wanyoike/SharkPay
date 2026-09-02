package com.sharkpay.payouts.events;

import com.sharkpay.payouts.domain.TransferState;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 (ADR 003) contract evidence for the transfers domain (owned by this
 * service): every transfers.transfer.*.v1 event is serialized (Jackson 3,
 * snake_case, NON_NULL) and structurally validated against
 * contracts/events/transfers.transfer.v1.json — read-only, binding. The
 * succeeded payload always carries the ledger entry id; the failed payload
 * carries the reason; nothing else travels.
 */
class TransferEventsTest {

    private final PayoutsTestEnv env = new PayoutsTestEnv();

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(value ->
                    value.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void everyPublishedEventValidatesAgainstTheTransferContractSchema() {
        env.createTransfer("k1", 25_000L);            // SUCCEEDED
        env.ledger.rejectPrefix("transfers:");        // next one fails at the ledger
        env.createTransfer.create("k2", PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET,
                10_000L, "KES", Map.of());

        List<CloudEvent> published = env.events.events();
        assertThat(published).hasSize(2);
        JsonSchemaCheck check = JsonSchemaCheck.load("transfers.transfer.v1.json");
        for (CloudEvent event : published) {
            JsonNode tree = mapper.valueToTree(event);
            assertThat(check.errors(tree))
                    .as("event %s must validate against transfers.transfer.v1.json:\n%s",
                            event.type(), tree)
                    .isEmpty();
        }
        assertThat(published).extracting(CloudEvent::type)
                .containsExactly(TransferEvents.SUCCEEDED, TransferEvents.FAILED);
    }

    @Test
    void theSucceededEventAlwaysCarriesTheLedgerEntryId() {
        var transfer = env.createTransfer("k1", 25_000L);

        CloudEvent event = env.events.eventsOfType(TransferEvents.SUCCEEDED).get(0);
        assertThat(event.subject()).isEqualTo(transfer.id());
        assertThat(event.source()).isEqualTo(CloudEvent.TRANSFERS_SOURCE);
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.occurredAt()).isEqualTo(PayoutsTestEnv.START);

        TransferEvents.TransferEventData data = (TransferEvents.TransferEventData) event.data();
        assertThat(data.transfer_id()).isEqualTo(transfer.id());
        assertThat(data.state()).isEqualTo(TransferState.SUCCEEDED.name());
        assertThat(data.amount().amount_minor()).isEqualTo(25_000);
        assertThat(data.amount().currency()).isEqualTo("KES");
        assertThat(data.amount().exponent()).isEqualTo(2);
        assertThat(data.fee().amount_minor()).isZero(); // V1 internal transfers are free
        assertThat(data.source_wallet()).isEqualTo(PayoutsTestEnv.WALLET);
        assertThat(data.destination_wallet()).isEqualTo(PayoutsTestEnv.OTHER_WALLET);
        assertThat(data.entry_id()).isEqualTo(transfer.entryId().toString());
        assertThat(data.reason()).isNull();
    }

    @Test
    void theFailedEventCarriesTheReasonAndNeverAnEntryId() {
        env.ledger.rejectPrefix("transfers:");
        var transfer = env.createTransfer.create("k1", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, 10_000L, "KES", Map.of()).transfer();

        CloudEvent event = env.events.eventsOfType(TransferEvents.FAILED).get(0);
        TransferEvents.TransferEventData data = (TransferEvents.TransferEventData) event.data();
        assertThat(data.transfer_id()).isEqualTo(transfer.id());
        assertThat(data.state()).isEqualTo(TransferState.FAILED.name());
        assertThat(data.reason()).contains("insufficient_funds");
        assertThat(data.entry_id()).isNull();
        // UUID v7 id for consumer dedupe
        assertThat(event.id()).matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }

    @Test
    void idempotentReplaysNeverReEmit() {
        env.createTransfer("k1", 25_000L);
        int eventsBefore = env.events.count();

        env.createTransfer.create("k1", PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET,
                25_000L, "KES", Map.of()); // replay

        assertThat(env.events.count()).isEqualTo(eventsBefore);
        assertThat(env.events.eventsOfType(TransferEvents.SUCCEEDED)).hasSize(1);
    }

    @Test
    void onlyTheTwoCatalogTypesAreEverPublished() {
        env.createTransfer("k1", 25_000L);
        env.ledger.rejectPrefix("transfers:");
        env.createTransfer.create("k2", PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET,
                10_000L, "KES", Map.of());

        List<String> types = env.events.events().stream().map(CloudEvent::type).distinct().toList();
        assertThat(types).containsExactlyInAnyOrder(TransferEvents.SUCCEEDED,
                TransferEvents.FAILED);
    }
}
