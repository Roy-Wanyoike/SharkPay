package com.sharkpay.payments.events;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 (ADR 003) contract evidence: every payments.payment.*.v1 event the
 * service publishes is serialized (Jackson 3, snake_case, NON_NULL) and
 * structurally validated against the merged schema
 * contracts/events/payments.payment.v1.json — read-only, binding.
 */
class PaymentEventsTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(value ->
                    value.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void everyPublishedEventValidatesAgainstTheMergedContractSchema() {
        driveFullLifecycle();

        List<CloudEvent> published = env.events.events();
        assertThat(published).isNotEmpty();
        JsonSchemaCheck check = JsonSchemaCheck.load("payments.payment.v1.json");
        for (CloudEvent event : published) {
            JsonNode tree = mapper.valueToTree(event);
            assertThat(check.errors(tree))
                    .as("event %s must validate against payments.payment.v1.json:\n%s",
                            event.type(), tree)
                    .isEmpty();
        }
    }

    @Test
    void onlyCatalogEventTypesArePublished() {
        driveFullLifecycle();
        // a cancel (no catalog type) and a blocked intent (no catalog type)
        // must never invent event types the schema does not list
        PaymentIntent cancellable = env.create("k-cancel");
        env.cancelPayment.cancel("ck", cancellable.id());
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.deny("velocity"));
        env.create("k-blocked");

        List<String> types = env.events.events().stream().map(CloudEvent::type).distinct().toList();
        assertThat(types).containsExactlyInAnyOrder(
                PaymentEvents.CREATED, PaymentEvents.PENDING_PROVIDER, PaymentEvents.SUCCEEDED,
                PaymentEvents.FAILED, PaymentEvents.EXPIRED, PaymentEvents.REVERSED);
    }

    @Test
    void everyEventCarriesAUniqueUuidV7Id() {
        driveFullLifecycle();

        List<String> ids = env.events.events().stream().map(CloudEvent::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        for (String id : ids) {
            // UUID v7: version nibble 7, RFC 9562 variant — consumers dedupe on it
            assertThat(id).matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
    }

    @Test
    void oneEventPerTransitionWithACatalogType() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        // idempotent replays never re-emit
        env.recordResult.record(null, intent.id(), "SUCCEEDED");
        assertThat(env.events.events()).hasSize(3);

        env.reversePayment.reverse("rk-1", intent.id(), null, "ops");

        // exactly the four catalog transitions of this lifecycle
        assertThat(env.events.events()).hasSize(4);
        assertThat(env.events.eventsOfType(PaymentEvents.CREATED)).hasSize(1);
        assertThat(env.events.eventsOfType(PaymentEvents.PENDING_PROVIDER)).hasSize(1);
        assertThat(env.events.eventsOfType(PaymentEvents.SUCCEEDED)).hasSize(1);
        assertThat(env.events.eventsOfType(PaymentEvents.REVERSED)).hasSize(1);
    }

    @Test
    void theSucceededPayloadCarriesMoneyFeeAndRefsExactly() {
        PaymentIntent intent = env.createDefault();
        env.events.reset();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        CloudEvent event = env.events.eventsOfType(PaymentEvents.SUCCEEDED).get(0);
        PaymentEvents.PaymentData data = (PaymentEvents.PaymentData) event.data();

        assertThat(event.subject()).isEqualTo(intent.id());
        assertThat(event.source()).isEqualTo("sharkpay/payments");
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.occurredAt()).isEqualTo(PaymentsTestEnv.START);

        assertThat(data.payment_id()).isEqualTo(intent.id());
        assertThat(data.state()).isEqualTo("SUCCEEDED");
        assertThat(data.amount().amount_minor()).isEqualTo(150_000);
        assertThat(data.amount().currency()).isEqualTo("KES");
        assertThat(data.amount().exponent()).isEqualTo(2);
        assertThat(data.fee().amount_minor()).isEqualTo(750);
        assertThat(data.destination_wallet()).isEqualTo(PaymentsTestEnv.WALLET);
        assertThat(data.rail()).isEqualTo("honeycoin");
        assertThat(data.entry_id()).isEqualTo(intent.captureEntryId());
        assertThat(data.provider_ref()).isEqualTo(intent.providerRef());
        assertThat(data.reason()).isNull();
    }

    @Test
    void theFailedAndExpiredPayloadsCarryReasonAndReleaseEntry() {
        PaymentIntent intent = env.createDefault();
        env.events.reset();
        env.recordResult.record(null, intent.id(), "FAILED");
        CloudEvent failedEvent = env.events.eventsOfType(PaymentEvents.FAILED).get(0);
        PaymentEvents.PaymentData failed =
                (PaymentEvents.PaymentData) failedEvent.data();
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.reason()).isEqualTo("provider_failed");
        assertThat(failed.entry_id()).isEqualTo(intent.releaseEntryId());

        PaymentIntent expiring = env.create("k-expiry");
        env.events.reset();
        env.clock.advance(java.time.Duration.ofSeconds(900));
        env.expirePayment.expire(expiring.id());
        CloudEvent expiredEvent = env.events.eventsOfType(PaymentEvents.EXPIRED).get(0);
        PaymentEvents.PaymentData expired =
                (PaymentEvents.PaymentData) expiredEvent.data();
        assertThat(expired.state()).isEqualTo("EXPIRED");
        assertThat(expired.reason()).isEqualTo("ttl_elapsed");
        assertThat(expired.entry_id()).isEqualTo(expiring.releaseEntryId());
    }

    @Test
    void theEnvelopeSerializesToTheContractShape() {
        PaymentIntent intent = env.createDefault();

        JsonNode tree = mapper.valueToTree(env.events.eventsOfType(PaymentEvents.CREATED).get(0));
        assertThat(tree.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "id", "type", "specversion", "source", "subject", "occurred_at", "data");
        assertThat(tree.get("occurred_at").asString()).startsWith("2026-09-01T");
        JsonNode data = tree.get("data");
        assertThat(data.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "payment_id", "state", "amount", "fee", "destination_wallet", "rail");
        assertThat(data.get("amount").get("amount_minor").asLong()).isEqualTo(150_000);
        // money is integral — never a float
        assertThat(data.get("amount").get("amount_minor").isIntegralNumber()).isTrue();
    }

    /** created → pending_provider → succeeded → reversed + a failed sibling. */
    private void driveFullLifecycle() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");
        env.reversePayment.reverse("rk-1", intent.id(), null, "ops");

        PaymentIntent failing = env.create("k-fail");
        env.recordResult.record(null, failing.id(), "FAILED");

        PaymentIntent expiring = env.create("k-expire");
        env.clock.advance(java.time.Duration.ofSeconds(900));
        env.expirePayment.expire(expiring.id());
    }
}
